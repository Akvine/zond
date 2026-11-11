package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * (long) (amount * 100): рубли в копейки через double. Двоичная дробь хранит 19.99 как 19.989999..., после
 * умножения выходит 1998.9999..., а приведение к целому дробную часть отбрасывает - получается 1998.
 */
@Component
public class FloatingScaleTruncationRule extends AbstractRule {
    private static final Set<String> INTEGER_TARGETS = Set.of("int", "long");
    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "Double", "Float");
    private static final Set<String> FLOATING_SOURCES =
            Set.of("parseDouble", "parseFloat", "doubleValue", "floatValue", "getAsDouble");
    // 10, 100, 1000, 1_000_000, 100.0
    private static final Pattern POWER_OF_TEN = Pattern.compile("1(_?0)+(\\.0)?[dDfFlL]?");
    private static final Pattern MONEY = Pattern.compile(
            "(?i)amount|price|cost|money|balance|payment|salary|fee|tariff|rub|kopeck|cent|penny|minor|сумм|цен");

    @Override
    public String code() {
        return RuleCodes.FLOATING_SCALE_TRUNCATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет дробное число, которое умножают на 100 или 1000 и приводят к целому без"
                + " округления: (long) (amount * 100)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (CastExpr cast : sourceFile.unit().findAll(CastExpr.class)) {
            Expression value = Nodes.unwrap(cast.getExpression());
            if (!INTEGER_TARGETS.contains(cast.getType().asString()) || !isScaledFraction(value)
                    || TestClasses.isInside(cast)) {
                continue;
            }
            boolean money = MONEY.matcher(Nodes.text(cast)).find();
            violations.add(violation(sourceFile, cast,
                    "'" + Nodes.text(cast) + "': приведение к " + cast.getType() + " отбрасывает дробную часть, а"
                            + " двоичная дробь после умножения часто чуть меньше целого - 19.99 * 100 дает"
                            + " 1998.9999... и превращается в 1998; округляйте через Math.round(...) либо считайте"
                            + " в BigDecimal")
                    .withLevel(money ? ErrorLevel.MAJOR : ErrorLevel.MINOR));
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

    // Отбросить дробную часть могли и намеренно: (int) (ratio * 100) для "не меньше стольких процентов"
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    private boolean isScaledFraction(Expression value) {
        if (!value.isBinaryExpr() || value.asBinaryExpr().getOperator() != BinaryExpr.Operator.MULTIPLY) {
            return false;
        }
        Expression left = Nodes.unwrap(value.asBinaryExpr().getLeft());
        Expression right = Nodes.unwrap(value.asBinaryExpr().getRight());
        return isPowerOfTen(right) && isFloating(left) || isPowerOfTen(left) && isFloating(right);
    }

    private boolean isPowerOfTen(Expression expression) {
        return expression.isLiteralExpr() && POWER_OF_TEN.matcher(expression.toString()).matches();
    }

    private boolean isFloating(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isBinaryExpr()) {
            return isFloating(value.asBinaryExpr().getLeft()) || isFloating(value.asBinaryExpr().getRight());
        }
        if (value.isMethodCallExpr()) {
            return FLOATING_SOURCES.contains(value.asMethodCallExpr().getNameAsString());
        }
        return value.isDoubleLiteralExpr() || LocalTypes.typeOf(value).filter(FLOATING_TYPES::contains).isPresent();
    }
}
