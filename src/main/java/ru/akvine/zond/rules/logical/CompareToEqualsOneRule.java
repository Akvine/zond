package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
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
import java.util.Optional;
import java.util.Set;

/**
 * Результат compareTo сравнивают с 1 или -1. Метод обязан вернуть только знак: у строк, дат и перечислений
 * это разность, и "больше" там бывает равно 7, а не 1.
 */
@Component
public class CompareToEqualsOneRule extends AbstractRule {
    private static final Set<String> COMPARE_TO = Set.of("compareTo", "compareToIgnoreCase");
    private static final String COMPARE = "compare";
    private static final String ONE = "1";

    // У этих типов compareTo по документации возвращает ровно -1, 0 или 1
    private static final Set<String> EXACT_TYPES = Set.of("BigDecimal", "BigInteger");
    // Числовые обертки сравнивают через Integer.compare и подобные: на деле там тоже ровно -1, 0 или 1
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "Integer", "Long", "Short", "Byte", "Double", "Float", "Character", "Boolean");

    @Override
    public String code() {
        return RuleCodes.COMPARE_TO_EQUALS_ONE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сравнение результата compareTo с 1 или -1 вместо сравнения с нулем";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr comparison : sourceFile.unit().findAll(BinaryExpr.class)) {
            boolean equal = comparison.getOperator() == BinaryExpr.Operator.EQUALS;
            if (!equal && comparison.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
                continue;
            }
            Expression left = Nodes.unwrap(comparison.getLeft());
            Expression right = Nodes.unwrap(comparison.getRight());
            Optional<Boolean> positive = sign(right).filter(found -> isCompare(left))
                    .or(() -> sign(left).filter(found -> isCompare(right)));
            positive.ifPresent(greater -> violations.add(violation(sourceFile, comparison,
                    "Результат сравнения проверяется на равенство " + (greater ? "1" : "-1") + ": метод обязан"
                            + " вернуть только знак - отрицательное число, ноль или положительное, - и у строк,"
                            + " дат и перечислений это разность, а не единица; условие окажется ложным, хотя"
                            + " порядок именно такой. Сравнивайте с нулем: '" + fix(equal, greater) + "'")));
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

    /**
     * @return true для 1, false для -1, пусто для всего остального
     */
    private Optional<Boolean> sign(Expression expression) {
        if (expression.isIntegerLiteralExpr()) {
            return ONE.equals(expression.asIntegerLiteralExpr().getValue()) ? Optional.of(true) : Optional.empty();
        }
        boolean minusOne = expression.isUnaryExpr()
                && expression.asUnaryExpr().getOperator() == UnaryExpr.Operator.MINUS
                && expression.asUnaryExpr().getExpression().isIntegerLiteralExpr()
                && ONE.equals(expression.asUnaryExpr().getExpression().asIntegerLiteralExpr().getValue());
        return minusOne ? Optional.of(false) : Optional.empty();
    }

    private boolean isCompare(Expression expression) {
        if (!expression.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        Optional<Expression> scope = call.getScope();
        if (COMPARE_TO.contains(call.getNameAsString()) && call.getArguments().size() == 1 && scope.isPresent()) {
            Optional<String> type = LocalTypes.typeOf(scope.get());
            return type.filter(name -> EXACT_TYPES.contains(name) || NUMERIC_TYPES.contains(name)).isEmpty();
        }
        // Integer.compare(a, b) возвращает ровно -1, 0 или 1; comparator.compare(a, b) - что угодно
        return COMPARE.equals(call.getNameAsString()) && call.getArguments().size() == 2 && scope.isPresent()
                && !NUMERIC_TYPES.contains(scope.get().toString());
    }

    private String fix(boolean equal, boolean greater) {
        if (equal) {
            return greater ? "> 0" : "< 0";
        }
        return greater ? "<= 0" : ">= 0";
    }
}
