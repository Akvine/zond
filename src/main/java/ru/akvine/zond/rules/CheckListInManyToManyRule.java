package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckListInManyToManyRule extends AbstractRule {
    private static final String MANY_TO_MANY = "ManyToMany";
    private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "Collection", "LinkedList");

    @Override
    public String code() {
        return RuleCodes.CHECK_LIST_IN_MANY_TO_MANY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет связи @ManyToMany, объявленные как List";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            if (!Annotations.has(field, MANY_TO_MANY)) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                if (LIST_TYPES.contains(LocalTypes.typeName(variable.getType()))) {
                    violations.add(violation(sourceFile, variable,
                            "@ManyToMany '" + variable.getNameAsString() + "' объявлена как "
                                    + LocalTypes.typeName(variable.getType()) + ": при удалении одного элемента"
                                    + " Hibernate удаляет все строки связи и вставляет оставшиеся заново;"
                                    + " используйте Set"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
