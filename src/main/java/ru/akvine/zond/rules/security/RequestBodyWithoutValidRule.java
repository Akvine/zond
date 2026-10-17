package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Constraints;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class RequestBodyWithoutValidRule extends AbstractRule implements ProjectRule {
    private static final String REQUEST_BODY = "RequestBody";
    private static final Set<String> VALIDATION_TRIGGERS = Set.of("Valid", "Validated");
    private static final Set<String> HTTP_CLIENTS =
            Set.of("FeignClient", "ReactiveFeignClient", "HttpExchange", "RegisterRestClient");

    // Простые тела запроса, у которых нет полей для проверки
    private static final Set<String> NOT_VALIDATED_TYPES = Set.of("String", "JsonNode", "Map", "Object");
    private static final Set<String> CONTAINERS = Set.of("List", "Set", "Collection", "Iterable", "Optional");
    // Элементы-значения: в List<Long> проверять нечего
    private static final Set<String> VALUE_TYPES = Set.of(
            "String", "Long", "Integer", "Short", "Byte", "Double", "Float", "Boolean", "Character", "BigDecimal",
            "BigInteger", "UUID", "LocalDate", "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime");
    // Свое ограничение проекта: @ValidPhone, @ValidEnum
    private static final String CUSTOM_CONSTRAINT_PREFIX = "Valid";

    @Override
    public String code() {
        return RuleCodes.REQUEST_BODY_WITHOUT_VALID_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @RequestBody без @Valid";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (Parameter parameter : sourceFile.unit().findAll(Parameter.class)) {
                if (Annotations.has(parameter, REQUEST_BODY) && !isOutgoing(parameter)
                        && !Annotations.hasAny(parameter, VALIDATION_TRIGGERS)
                        && needsValidation(parameter.getType(), sourceFile.unit(), classes)) {
                    violations.add(violation(sourceFile, parameter,
                            "@RequestBody '" + parameter.getNameAsString() + "' без @Valid: аннотации валидации на"
                                    + " полях DTO не сработают, в сервис попадут непроверенные данные;"
                                    + " добавьте @Valid"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    /**
     * Тело, в котором есть что проверять. Класс проекта - если в нем есть ограничения; класс библиотеки
     * (Update из клиента Telegram) - нет: ограничений в нем мы не ставили. Класс, которого не видно в проверке
     * и который не импортирован из библиотеки, считаем своим DTO.
     */
    private boolean needsValidation(Type declared, CompilationUnit unit, ProjectClasses classes) {
        Type body = elementType(declared);
        String name = LocalTypes.typeName(body);
        if (body.isArrayType() || !body.isClassOrInterfaceType() || NOT_VALIDATED_TYPES.contains(name)
                || body != declared && VALUE_TYPES.contains(name)) {
            return false;
        }
        Optional<ClassOrInterfaceDeclaration> dto = classes.resolve(body.asClassOrInterfaceType());
        if (dto.isPresent()) {
            return hasConstraints(dto.get(), classes, Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        return !isImported(name, unit);
    }

    // @FeignClient и @HttpExchange описывают запрос, который приложение само отправляет другому сервису
    private boolean isOutgoing(Parameter parameter) {
        return parameter.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> Annotations.hasAny(type, HTTP_CLIENTS))
                .isPresent();
    }

    // List<OrderDto> -> OrderDto
    private Type elementType(Type type) {
        if (type.isClassOrInterfaceType() && CONTAINERS.contains(LocalTypes.typeName(type))) {
            Optional<Type> argument = type.asClassOrInterfaceType().getTypeArguments()
                    .filter(arguments -> arguments.size() == 1)
                    .map(arguments -> arguments.get(0));
            if (argument.isPresent()) {
                return argument.get();
            }
        }
        return type;
    }

    private boolean hasConstraints(ClassOrInterfaceDeclaration dto, ProjectClasses classes,
                                   Set<ClassOrInterfaceDeclaration> visited) {
        if (!visited.add(dto)) {
            return false;
        }
        for (FieldDeclaration field : dto.getFields()) {
            if (Constraints.hasAny(field) || field.getAnnotations().stream().anyMatch(this::isCustomConstraint)) {
                return true;
            }
            Type fieldType = elementType(field.getElementType());
            if (Annotations.has(field, Constraints.VALID) && fieldType.isClassOrInterfaceType()) {
                Optional<ClassOrInterfaceDeclaration> nested = classes.resolve(fieldType.asClassOrInterfaceType());
                if (nested.isPresent() && hasConstraints(nested.get(), classes, visited)) {
                    return true;
                }
            }
        }
        return classes.parent(dto).filter(parent -> hasConstraints(parent, classes, visited)).isPresent();
    }

    private boolean isCustomConstraint(AnnotationExpr annotation) {
        String name = annotation.getName().getIdentifier();
        return name.startsWith(CUSTOM_CONSTRAINT_PREFIX) && name.length() > CUSTOM_CONSTRAINT_PREFIX.length();
    }

    // Класс подключен из библиотеки: import org.telegram.telegrambots.meta.api.objects.Update
    private boolean isImported(String name, CompilationUnit unit) {
        return unit.getImports().stream()
                .filter(declaration -> !declaration.isAsterisk() && !declaration.isStatic())
                .anyMatch(declaration -> declaration.getName().getIdentifier().equals(name));
    }
}
