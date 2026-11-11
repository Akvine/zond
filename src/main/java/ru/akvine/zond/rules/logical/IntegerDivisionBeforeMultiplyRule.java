package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Процент через done / total * 100 при целых done и total: деление выполняется первым и отбрасывает дробную
 * часть, поэтому до самого конца получается 0, а в конце - сразу 100.
 */
@Component
public class IntegerDivisionBeforeMultiplyRule extends AbstractRule {
    private static final Set<String> INTEGER_TYPES =
            Set.of("int", "long", "short", "byte", "Integer", "Long", "Short", "Byte");
    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "Double", "Float");
    // Методы, которые возвращают целое число
    private static final Set<String> INTEGER_METHODS = Set.of("size", "length", "count");
    private static final String LENGTH = "length";

    @Override
    public String code() {
        return RuleCodes.INTEGER_DIVISION_BEFORE_MULTIPLY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет целочисленное деление, результат которого затем умножают: done / total * 100";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Деление, которое записывают в double, показывает jr:28: потеря та же, а совет там свой
        Set<BinaryExpr> floating = Collections.newSetFromMap(new IdentityHashMap<>());
        LocalTypes.findTypedExpressions(sourceFile.unit(), this::hasDivision).stream()
                .filter(typed -> FLOATING_TYPES.contains(typed.targetType()))
                .forEach(typed -> floating.addAll(typed.expression().findAll(BinaryExpr.class)));
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr product : sourceFile.unit().findAll(BinaryExpr.class)) {
            if (product.getOperator() != BinaryExpr.Operator.MULTIPLY || TestClasses.isInside(product)) {
                continue;
            }
            Optional<BinaryExpr> division = integerDivision(product.getLeft(), product.getRight())
                    .or(() -> integerDivision(product.getRight(), product.getLeft()));
            if (division.isPresent() && !floating.contains(product)) {
                violations.add(violation(sourceFile, product,
                        "'" + Nodes.text(product) + "': целые числа сначала делятся, и дробная часть пропадает еще"
                                + " до умножения - для 1 из 3 получится 0, а не 33; умножайте до деления"
                                + " (a * 100 / b) либо считайте в double"));
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

    // Типы операндов видны не всегда: что считалось целым, а что нет, по одному файлу можно и не узнать
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    private boolean hasDivision(Expression expression) {
        return expression.findAll(BinaryExpr.class).stream()
                .anyMatch(binary -> binary.getOperator() == BinaryExpr.Operator.DIVIDE);
    }

    /**
     * @param candidate множитель, который может оказаться целочисленным делением
     * @param factor    второй множитель
     */
    private Optional<BinaryExpr> integerDivision(Expression candidate, Expression factor) {
        Expression value = Nodes.unwrap(candidate);
        if (!value.isBinaryExpr() || value.asBinaryExpr().getOperator() != BinaryExpr.Operator.DIVIDE) {
            return Optional.empty();
        }
        BinaryExpr division = value.asBinaryExpr();
        Expression divisor = Nodes.unwrap(division.getRight());
        // seconds / 60 * 60 - округление вниз до целой минуты: дробную часть отбрасывают намеренно
        boolean roundsDown = divisor.toString().equals(Nodes.unwrap(factor).toString());
        // amount / 1000 * 10 - выделение разряда: и делитель, и множитель заданы числами, остаток отбрасывают
        // намеренно. 60 / 2 * count - константа, ее значение видно сразу
        boolean deliberate = isLiteral(divisor) && (isLiteral(factor) || isLiteral(division.getLeft()));
        boolean integer = isInteger(division.getLeft()) && isInteger(divisor);
        return integer && !roundsDown && !deliberate ? Optional.of(division) : Optional.empty();
    }

    private boolean isLiteral(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value.isIntegerLiteralExpr() || value.isLongLiteralExpr();
    }

    private boolean isInteger(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (isLiteral(value)) {
            return true;
        }
        if (value.isMethodCallExpr()) {
            return INTEGER_METHODS.contains(value.asMethodCallExpr().getNameAsString())
                    && value.asMethodCallExpr().getArguments().isEmpty();
        }
        if (value.isFieldAccessExpr() && !value.asFieldAccessExpr().getScope().isThisExpr()) {
            return LENGTH.equals(value.asFieldAccessExpr().getNameAsString());
        }
        return LocalTypes.typeOf(value).filter(INTEGER_TYPES::contains).isPresent();
    }
}
