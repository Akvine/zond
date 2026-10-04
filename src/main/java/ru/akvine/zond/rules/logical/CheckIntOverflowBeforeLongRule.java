package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckIntOverflowBeforeLongRule extends AbstractRule {
    private static final Set<String> LONG_TYPES = Set.of("long", "Long");
    private static final Set<String> INT_TYPES = Set.of("int", "short", "byte", "Integer", "Short", "Byte");
    private static final Set<String> INT_METHODS = Set.of("size", "length");
    private static final String LENGTH = "length";

    private static final Set<BinaryExpr.Operator> ARITHMETIC = Set.of(
            BinaryExpr.Operator.PLUS, BinaryExpr.Operator.MINUS, BinaryExpr.Operator.MULTIPLY,
            BinaryExpr.Operator.DIVIDE, BinaryExpr.Operator.REMAINDER);

    /**
     * @param isInt    выражение вычисляется в int
     * @param constant значение, если оно известно на этапе разбора
     */
    private record Operand(boolean isInt, Long constant) {
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_INT_OVERFLOW_BEFORE_LONG_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет умножение int, результат которого записывается в long";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (LocalTypes.TypedExpression typed : LocalTypes.findTypedExpressions(sourceFile.unit())) {
            if (!LONG_TYPES.contains(typed.targetType())) {
                continue;
            }

            List<BinaryExpr> overflows = new ArrayList<>();
            evaluate(typed.expression(), overflows);
            if (!overflows.isEmpty()) {
                BinaryExpr multiplication = overflows.get(0);
                violations.add(violation(sourceFile, multiplication,
                        "Умножение int в '" + multiplication + "' записывается в long: оно выполняется в int"
                                + " и может переполниться еще до расширения; приведите один из операндов"
                                + " к long (например, 1000L)"));
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

    private Operand evaluate(Expression expression, List<BinaryExpr> overflows) {
        Expression value = Nodes.unwrap(expression);
        if (value.isIntegerLiteralExpr()) {
            return new Operand(true, parse(value.asIntegerLiteralExpr().getValue()));
        }
        if (!value.isBinaryExpr()) {
            return new Operand(isInt(value), null);
        }

        BinaryExpr binary = value.asBinaryExpr();
        if (!ARITHMETIC.contains(binary.getOperator())) {
            return new Operand(false, null);
        }

        Operand left = evaluate(binary.getLeft(), overflows);
        Operand right = evaluate(binary.getRight(), overflows);
        boolean isInt = left.isInt() && right.isInt();
        if (!isInt || binary.getOperator() != BinaryExpr.Operator.MULTIPLY) {
            return new Operand(isInt, null);
        }

        // Произведение констант считаем: 24 * 60 * 60 * 1000 в int помещается, а умноженное еще на 30 - уже нет
        if (left.constant() != null && right.constant() != null) {
            long product = left.constant() * right.constant();
            if (product > Integer.MAX_VALUE || product < Integer.MIN_VALUE) {
                overflows.add(binary);
            }
            return new Operand(true, product);
        }

        overflows.add(binary);
        return new Operand(true, null);
    }

    private boolean isInt(Expression value) {
        if (value.isMethodCallExpr()) {
            return INT_METHODS.contains(value.asMethodCallExpr().getNameAsString())
                    && value.asMethodCallExpr().getArguments().isEmpty();
        }
        if (value.isFieldAccessExpr() && !value.asFieldAccessExpr().getScope().isThisExpr()) {
            return LENGTH.equals(value.asFieldAccessExpr().getNameAsString());
        }
        return LocalTypes.typeOf(value).filter(INT_TYPES::contains).isPresent();
    }

    private Long parse(String literal) {
        try {
            return Long.parseLong(literal.replace("_", ""));
        } catch (NumberFormatException exception) {
            // 0xFF, 0b1010 и т.п.
            return null;
        }
    }
}
