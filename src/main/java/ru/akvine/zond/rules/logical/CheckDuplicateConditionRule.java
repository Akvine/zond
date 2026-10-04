package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.stmt.IfStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckDuplicateConditionRule extends AbstractRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_DUPLICATE_CONDITION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет одинаковые условия в цепочке if / else if";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (IfStmt first : sourceFile.unit().findAll(IfStmt.class)) {
            if (isElseIf(first)) {
                continue;
            }
            // Идем по цепочке от первого if: повторное условие никогда не выполнится - его ветку уже заняло первое
            Set<String> conditions = new HashSet<>();
            Optional<IfStmt> current = Optional.of(first);
            while (current.isPresent()) {
                IfStmt branch = current.get();
                if (!conditions.add(branch.getCondition().toString())) {
                    violations.add(violation(sourceFile, branch,
                            "Условие '" + branch.getCondition() + "' уже проверялось выше в этой же цепочке:"
                                    + " до этой ветки выполнение не дойдет никогда; скорее всего, условие"
                                    + " скопировали и забыли изменить"));
                }
                current = branch.getElseStmt().filter(next -> next instanceof IfStmt).map(next -> (IfStmt) next);
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
        return ErrorType.LOGICAL;
    }

    private boolean isElseIf(IfStmt branch) {
        return branch.getParentNode()
                .filter(parent -> parent instanceof IfStmt outer
                        && outer.getElseStmt().filter(next -> next == branch).isPresent())
                .isPresent();
    }
}
