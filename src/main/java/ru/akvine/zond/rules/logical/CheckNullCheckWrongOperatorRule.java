package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
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
public class CheckNullCheckWrongOperatorRule extends AbstractRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_NULL_CHECK_WRONG_OPERATOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет проверку на null, после которой из-за неверного оператора идет обращение к null";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        // В цепочке a && b && c одно и то же место видно из нескольких вложенных выражений
        Set<Integer> reportedLines = new HashSet<>();
        for (BinaryExpr condition : sourceFile.unit().findAll(BinaryExpr.class)) {
            boolean isAnd = condition.getOperator() == BinaryExpr.Operator.AND;
            if (!isAnd && condition.getOperator() != BinaryExpr.Operator.OR) {
                continue;
            }
            // x == null && x.foo(): правая часть выполняется, как раз когда x - null.
            // x != null || x.foo(): то же самое
            BinaryExpr.Operator failing = isAnd ? BinaryExpr.Operator.EQUALS : BinaryExpr.Operator.NOT_EQUALS;
            List<Expression> checks = new ArrayList<>();
            flatten(condition.getLeft(), condition.getOperator(), checks);
            checks.stream()
                    .map(check -> comparedWithNull(check, failing))
                    .flatMap(Optional::stream)
                    .filter(variable -> dereferences(condition.getRight(), variable))
                    .findFirst()
                    .filter(variable -> reportedLines.add(condition.getBegin().map(position -> position.line).orElse(0)))
                    .ifPresent(variable -> violations.add(violation(sourceFile, condition,
                            "'" + condition + "': к '" + variable + "' обращаются именно тогда, когда она null -"
                                    + " будет NullPointerException; замените оператор: "
                                    + (isAnd ? "== null ||" : "!= null &&"))));
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

    private void flatten(Expression expression, BinaryExpr.Operator operator, List<Expression> operands) {
        Expression value = Nodes.unwrap(expression);
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == operator) {
            flatten(value.asBinaryExpr().getLeft(), operator, operands);
            flatten(value.asBinaryExpr().getRight(), operator, operands);
        } else {
            operands.add(value);
        }
    }

    /**
     * @return переменная, которую выражение сравнивает с null заданным оператором
     */
    private Optional<String> comparedWithNull(Expression expression, BinaryExpr.Operator operator) {
        if (!expression.isBinaryExpr() || expression.asBinaryExpr().getOperator() != operator) {
            return Optional.empty();
        }
        Expression left = Nodes.unwrap(expression.asBinaryExpr().getLeft());
        Expression right = Nodes.unwrap(expression.asBinaryExpr().getRight());
        if (right.isNullLiteralExpr()) {
            return Optional.of(left.toString());
        }
        return left.isNullLiteralExpr() ? Optional.of(right.toString()) : Optional.empty();
    }

    // variable.method() либо variable.field
    private boolean dereferences(Expression expression, String variable) {
        boolean callsMethod = expression.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getScope().filter(scope -> scope.toString().equals(variable)).isPresent());
        return callsMethod || expression.findAll(FieldAccessExpr.class).stream()
                .anyMatch(access -> access.getScope().toString().equals(variable));
    }
}
