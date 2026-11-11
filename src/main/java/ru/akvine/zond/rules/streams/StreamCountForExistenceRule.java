package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamChains;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class StreamCountForExistenceRule extends AbstractRule {
    private static final String COUNT = "count";
    private static final String FILTER = "filter";

    private static final String ZERO = "0";
    private static final String ONE = "1";
    // count() > 0, count() != 0, count() == 0, count() <= 0
    private static final Set<BinaryExpr.Operator> ZERO_CHECKS = Set.of(
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.EQUALS,
            BinaryExpr.Operator.LESS_EQUALS);
    // count() >= 1, count() < 1. А count() == 1 и count() > 1 - это "ровно один" и "больше одного":
    // первого совпадения для ответа мало, и anyMatch их не заменит
    private static final Set<BinaryExpr.Operator> ONE_CHECKS = Set.of(
            BinaryExpr.Operator.GREATER_EQUALS, BinaryExpr.Operator.LESS);
    // 0 < count() - то же, что count() > 0
    private static final Map<BinaryExpr.Operator, BinaryExpr.Operator> MIRRORED = Map.of(
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.LESS,
            BinaryExpr.Operator.LESS, BinaryExpr.Operator.GREATER,
            BinaryExpr.Operator.GREATER_EQUALS, BinaryExpr.Operator.LESS_EQUALS,
            BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER_EQUALS);

    @Override
    public String code() {
        return RuleCodes.STREAM_COUNT_FOR_EXISTENCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет проверку существования элемента через filter().count()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(BinaryExpr.class).stream()
                .filter(this::isExistenceCheck)
                .map(comparison -> violation(sourceFile, comparison,
                        "Проверка существования через count() в '" + comparison + "': count() обходит весь стрим,"
                                + " хотя достаточно первого совпадения; используйте anyMatch(...) или noneMatch(...)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    // ...filter(predicate)...count()
    private boolean isFilteredCount(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value.isMethodCallExpr()
                && COUNT.equals(value.asMethodCallExpr().getNameAsString())
                && value.asMethodCallExpr().getArguments().isEmpty()
                && StreamChains.callsBefore(value.asMethodCallExpr()).stream()
                .anyMatch(call -> FILTER.equals(call.getNameAsString()));
    }

    private boolean isExistenceCheck(BinaryExpr comparison) {
        BinaryExpr.Operator operator = comparison.getOperator();
        if (isFilteredCount(comparison.getLeft())) {
            return isExistenceBound(operator, comparison.getRight());
        }
        return isFilteredCount(comparison.getRight())
                && isExistenceBound(MIRRORED.getOrDefault(operator, operator), comparison.getLeft());
    }

    // Сравнение записано так, будто count() стоит слева
    private boolean isExistenceBound(BinaryExpr.Operator operator, Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isIntegerLiteralExpr() && !value.isLongLiteralExpr()) {
            return false;
        }
        String bound = value.toString().replaceAll("[lL]$", "");
        return ZERO.equals(bound) && ZERO_CHECKS.contains(operator) || ONE.equals(bound) && ONE_CHECKS.contains(operator);
    }
}
