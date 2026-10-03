package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckIdenticalOperandsRule extends AbstractRule {
    // Для + и * одинаковые операнды - обычное дело (a + a, a * a), поэтому их здесь нет
    private static final Set<BinaryExpr.Operator> OPERATORS = Set.of(
            BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS,
            BinaryExpr.Operator.AND, BinaryExpr.Operator.OR,
            BinaryExpr.Operator.BINARY_AND, BinaryExpr.Operator.BINARY_OR, BinaryExpr.Operator.XOR,
            BinaryExpr.Operator.MINUS, BinaryExpr.Operator.DIVIDE, BinaryExpr.Operator.REMAINDER,
            BinaryExpr.Operator.LESS, BinaryExpr.Operator.GREATER,
            BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER_EQUALS);

    @Override
    public String code() {
        return RuleCodes.CHECK_IDENTICAL_OPERANDS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет операции с одинаковыми операндами: a == a, a && a, a - a";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(BinaryExpr.class).stream()
                .filter(binary -> OPERATORS.contains(binary.getOperator()))
                .filter(binary -> !Nodes.unwrap(binary.getLeft()).isLiteralExpr())
                .filter(binary -> !hasSideEffects(binary.getLeft()))
                .filter(binary -> Nodes.text(Nodes.unwrap(binary.getLeft()))
                        .equals(Nodes.text(Nodes.unwrap(binary.getRight()))))
                .map(binary -> violation(sourceFile, binary,
                        "Одинаковые операнды в '" + binary + "': результат известен заранее,"
                                + " скорее всего во втором операнде опечатка"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // next() == next(), i++ < i++: одинаковый текст, но разные значения
    private boolean hasSideEffects(Expression expression) {
        return !expression.findAll(MethodCallExpr.class).isEmpty()
                || !expression.findAll(ObjectCreationExpr.class).isEmpty()
                || !expression.findAll(AssignExpr.class).isEmpty()
                || expression.findAll(UnaryExpr.class).stream()
                .anyMatch(unary -> unary.getOperator().name().endsWith("INCREMENT")
                        || unary.getOperator().name().endsWith("DECREMENT"));
    }
}
