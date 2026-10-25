package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import java.util.regex.Pattern;

@Component
public class FloatingPointEqualityRule extends AbstractRule {
    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "Double", "Float");
    // Бесконечности, крайние значения и константа нуля: сравнение с ними точное
    private static final Pattern EXACT_CONSTANT = Pattern.compile(
            "(.*\\.)?(POSITIVE_INFINITY|NEGATIVE_INFINITY|MAX_VALUE|MIN_VALUE|ZERO)");
    private static final Set<String> INTEGRAL_TYPES = Set.of("int", "long", "short", "byte");
    private static final Set<String> ROUNDING = Set.of("floor", "ceil", "rint", "round");
    private static final String EQUALS = "equals";
    // 0, 0.0, 0d, 0.0f, -0.0
    private static final Pattern ZERO = Pattern.compile("^-?0(\\.0*)?[dDfF]?$");

    @Override
    public String code() {
        return RuleCodes.FLOATING_POINT_EQUALITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сравнение float и double через == и !=";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr comparison : sourceFile.unit().findAll(BinaryExpr.class)) {
            if (comparison.getOperator() != BinaryExpr.Operator.EQUALS
                    && comparison.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
                continue;
            }

            Expression left = Nodes.unwrap(comparison.getLeft());
            Expression right = Nodes.unwrap(comparison.getRight());

            // x != x - принятый способ проверки на NaN; одинаковые операнды ловит отдельное правило
            // x == 0 - проверка перед делением либо "значение не задано": ноль представим точно
            if (left.isNullLiteralExpr() || right.isNullLiteralExpr() || left.equals(right)
                    || isZero(left) || isZero(right)) {
                continue;
            }
            // x == Double.POSITIVE_INFINITY, entry == ZERO: значения представимы точно.
            // longValue == value при long longValue = (long) value, Math.floor(x) == x: проверка, что число целое.
            // equals(Object) сравнивает поля точно по смыслу
            if (isExactConstant(left) || isExactConstant(right) || isIntegerPartOf(left, right)
                    || isIntegerPartOf(right, left) || isInEquals(comparison)) {
                continue;
            }

            if (isFloating(left) || isFloating(right)) {
                violations.add(violation(sourceFile, comparison,
                        "Сравнение чисел с плавающей точкой через " + comparison.getOperator().asString()
                                + " в '" + comparison + "': из-за погрешности вычислений 0.1 + 0.2 != 0.3;"
                                + " сравнивайте с допуском (Math.abs(a - b) < eps) или используйте BigDecimal"));
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

    private boolean isFloating(Expression expression) {
        return LocalTypes.typeOf(expression).filter(FLOATING_TYPES::contains).isPresent();
    }

    private boolean isZero(Expression expression) {
        return ZERO.matcher(expression.toString()).matches();
    }

    private boolean isExactConstant(Expression expression) {
        return EXACT_CONSTANT.matcher(expression.toString()).matches();
    }

    // (long) value, Math.floor(value) - либо переменная с таким начальным значением - против самого value
    private boolean isIntegerPartOf(Expression integral, Expression value) {
        Expression candidate = Nodes.unwrap(integral);
        if (candidate.isNameExpr()) {
            candidate = LocalTypes.findInitializer(candidate).map(Nodes::unwrap).orElse(candidate);
        }
        String text = Nodes.unwrap(value).toString();
        if (candidate.isCastExpr()) {
            return INTEGRAL_TYPES.contains(candidate.asCastExpr().getType().asString())
                    && Nodes.unwrap(candidate.asCastExpr().getExpression()).toString().equals(text);
        }
        return candidate.isMethodCallExpr() && ROUNDING.contains(candidate.asMethodCallExpr().getNameAsString())
                && candidate.asMethodCallExpr().getArguments().size() == 1
                && Nodes.unwrap(candidate.asMethodCallExpr().getArgument(0)).toString().equals(text);
    }

    private boolean isInEquals(Node node) {
        return node.findAncestor(MethodDeclaration.class)
                .filter(method -> EQUALS.equals(method.getNameAsString()) && method.getParameters().size() == 1)
                .isPresent();
    }
}
