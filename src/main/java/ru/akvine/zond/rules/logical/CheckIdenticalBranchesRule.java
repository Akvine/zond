package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckIdenticalBranchesRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_IDENTICAL_BRANCHES_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет одинаковые ветки if / else и повторяющиеся условия в цепочке else if";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (IfStmt ifStmt : sourceFile.unit().findAll(IfStmt.class)) {
            Optional<Statement> elseBranch = ifStmt.getElseStmt().filter(branch -> !branch.isIfStmt());
            if (elseBranch.isPresent() && Nodes.text(ifStmt.getThenStmt()).equals(Nodes.text(elseBranch.get()))) {
                violations.add(violation(sourceFile, ifStmt,
                        "Ветки if и else одинаковы: условие '" + ifStmt.getCondition() + "' ни на что не влияет"));
            }

            if (isChainStart(ifStmt)) {
                reportRepeatedConditions(sourceFile, ifStmt, violations);
            }
        }

        for (ConditionalExpr ternary : sourceFile.unit().findAll(ConditionalExpr.class)) {
            if (Nodes.text(ternary.getThenExpr()).equals(Nodes.text(ternary.getElseExpr()))) {
                violations.add(violation(sourceFile, ternary,
                        "Обе ветки тернарного оператора одинаковы: условие '" + ternary.getCondition()
                                + "' ни на что не влияет"));
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

    // Первый if в цепочке if / else if / else if
    private boolean isChainStart(IfStmt ifStmt) {
        return ifStmt.getParentNode()
                .filter(parent -> parent instanceof IfStmt outer
                        && outer.getElseStmt().filter(branch -> branch == ifStmt).isPresent())
                .isEmpty();
    }

    private void reportRepeatedConditions(SourceFile sourceFile, IfStmt start, List<Violation> violations) {
        Set<String> conditions = new HashSet<>();
        IfStmt current = start;
        while (current != null) {
            if (!conditions.add(Nodes.text(current.getCondition()))) {
                violations.add(violation(sourceFile, current,
                        "Условие '" + current.getCondition() + "' уже проверялось выше в этой цепочке else if:"
                                + " ветка никогда не выполнится"));
            }
            current = current.getElseStmt()
                    .filter(Statement::isIfStmt)
                    .map(Statement::asIfStmt)
                    .orElse(null);
        }
    }
}
