package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class CheckAssignmentInConditionRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_ASSIGNMENT_IN_CONDITION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет присваивание на месте условия: if (flag = true)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (Node node : sourceFile.unit().findAll(Node.class)) {
            // Условие целиком должно быть присваиванием. Идиома while ((line = reader.readLine()) != null)
            // сюда не попадает: там условие - сравнение
            findCondition(node)
                    .map(Nodes::unwrap)
                    .filter(condition -> condition.isAssignExpr()
                            && condition.asAssignExpr().getOperator() == AssignExpr.Operator.ASSIGN)
                    .ifPresent(condition -> violations.add(violation(sourceFile, condition,
                            "Присваивание на месте условия в '" + condition + "': вместо сравнения переменной"
                                    + " записывается значение, и условие от нее больше не зависит;"
                                    + " скорее всего имелось в виду ==")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private Optional<Expression> findCondition(Node node) {
        if (node instanceof IfStmt ifStmt) {
            return Optional.of(ifStmt.getCondition());
        }
        if (node instanceof WhileStmt loop) {
            return Optional.of(loop.getCondition());
        }
        if (node instanceof DoStmt loop) {
            return Optional.of(loop.getCondition());
        }
        if (node instanceof ConditionalExpr ternary) {
            return Optional.of(ternary.getCondition());
        }
        return Optional.empty();
    }
}
