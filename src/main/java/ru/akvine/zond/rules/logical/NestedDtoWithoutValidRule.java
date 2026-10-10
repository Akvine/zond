package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class NestedDtoWithoutValidRule extends AbstractRule implements ProjectRule {
    private static final String ENTITY = "Entity";
    private static final String VALID_ANNOTATION = "@" + Constraints.VALID;

    @Override
    public String code() {
        return RuleCodes.NESTED_DTO_WITHOUT_VALID_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет вложенные объекты с ограничениями, на поле которых нет @Valid";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // Классы, в полях которых есть ограничения, и классы, которые кто-то проверяет через @Valid
        Set<String> constrained = new HashSet<>();
        Set<String> validated = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.getFields().stream().anyMatch(Constraints::hasAny)) {
                    constrained.add(type.getNameAsString());
                }
            }
            for (Parameter parameter : sourceFile.unit().findAll(Parameter.class)) {
                if (Annotations.has(parameter, Constraints.VALID) || Annotations.has(parameter, Constraints.VALIDATED)) {
                    // List<OrderLine> lines: проверяется и сам список, и его элементы
                    parameter.getType().findAll(ClassOrInterfaceType.class)
                            .forEach(used -> validated.add(used.getNameAsString()));
                    validated.add(LocalTypes.typeName(parameter.getType()));
                }
            }
        }

        // Ограничения работают только там, куда заходит валидатор: в классах, достижимых от параметра с @Valid.
        // @NotNull в ответе или во внутреннем объекте никто не проверяет, и @Valid над его полем ничего не изменит
        Set<String> reachable = reachableFrom(validated, sourceFiles);

        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                // Проверяют сам класс - значит, ждут, что проверится и вложенное. Сущности JPA проверяет Hibernate.
                // Если параметров с @Valid в проверке нет вовсе, судим по самим ограничениям, как раньше
                boolean isChecked = validated.isEmpty()
                        ? constrained.contains(type.getNameAsString())
                        : reachable.contains(type.getNameAsString());
                if (!isChecked || Annotations.has(type, ENTITY) || TestClasses.isInside(type)) {
                    continue;
                }
                for (FieldDeclaration field : type.getFields()) {
                    findNested(field, constrained)
                            .filter(nested -> !field.toString().contains(VALID_ANNOTATION))
                            .ifPresent(nested -> violations.add(violation(sourceFile, field,
                                    "Поле '" + field.getVariable(0).getNameAsString() + "' класса '"
                                            + type.getNameAsString() + "' без @Valid: в '" + nested + "' есть"
                                            + " ограничения, но при проверке внешнего объекта во вложенный"
                                            + " валидатор не заходит, и они молча пропускаются; добавьте @Valid")));
                }
            }
        }
        return violations;
    }

    // Классы, до которых валидатор может дойти от проверяемых параметров: по типам полей и их параметрам
    private Set<String> reachableFrom(Set<String> roots, List<SourceFile> sourceFiles) {
        Map<String, Set<String>> fieldTypes = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                Set<String> names = fieldTypes.computeIfAbsent(type.getNameAsString(), name -> new HashSet<>());
                type.getFields().forEach(field -> field.findAll(ClassOrInterfaceType.class)
                        .forEach(used -> names.add(used.getNameAsString())));
                type.getExtendedTypes().forEach(parent -> names.add(parent.getNameAsString()));
            }
        }
        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (reachable.add(name)) {
                queue.addAll(fieldTypes.getOrDefault(name, Set.of()));
            }
        }
        return reachable;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Тип поля либо тип элементов: Address, List<Item>, Map<String, Item>
    private Optional<String> findNested(FieldDeclaration field, Set<String> constrained) {
        return field.getVariable(0).getType().findAll(ClassOrInterfaceType.class).stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .filter(constrained::contains)
                .findFirst();
    }
}
