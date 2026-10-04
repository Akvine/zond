package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class CheckUnboundedRequestCollectionRule extends AbstractRule {
    private static final String SIZE = "Size";
    private static final Set<String> COLLECTION_TYPES = Set.of("List", "Set", "Collection", "Map", "ArrayList");

    // Типы не разрешаем: о том, что класс принимает данные запроса, судим по имени либо по аннотациям валидации
    private static final Pattern REQUEST_NAME = Pattern.compile(".*(Request|Form|Command|Payload)$");
    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "NotNull", "NotBlank", "NotEmpty", "Valid", "Min", "Max", "Pattern", "Positive", "PositiveOrZero",
            "Email", "Size");

    @Override
    public String code() {
        return RuleCodes.CHECK_UNBOUNDED_REQUEST_COLLECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет коллекции во входных DTO без ограничения размера (@Size)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface() || !isRequestBody(type)) {
                continue;
            }

            for (FieldDeclaration field : type.getFields()) {
                boolean isCollection = field.getVariables().stream()
                        .anyMatch(variable -> COLLECTION_TYPES.contains(LocalTypes.typeName(variable.getType()))
                                || variable.getType().isArrayType());
                if (isCollection && !field.isStatic() && !Annotations.has(field, SIZE)) {
                    violations.add(violation(sourceFile, field,
                            "Коллекция '" + fieldNames(field) + "' во входном DTO '" + type.getNameAsString()
                                    + "' без @Size: клиент может прислать сколько угодно элементов и исчерпать"
                                    + " память или процессор; ограничьте размер: @Size(max = ...)"));
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

    private boolean isRequestBody(ClassOrInterfaceDeclaration type) {
        return REQUEST_NAME.matcher(type.getNameAsString()).matches()
                || type.getFields().stream().anyMatch(field -> Annotations.hasAny(field, VALIDATION_ANNOTATIONS));
    }

    private String fieldNames(FieldDeclaration field) {
        return field.getVariables().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.joining(", "));
    }
}
