package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckStreamCountForExistenceRule extends AbstractRule {
    private static final String COUNT = "count";
    private static final String FILTER = "filter";

    // count() > 0, count() != 0, count() == 0, count() >= 1, count() < 1
    private static final Set<String> EXISTENCE_BOUNDS = Set.of("0", "1", "0L", "1L");
    private static final Set<BinaryExpr.Operator> COMPARISONS = Set.of(
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS, BinaryExpr.Operator.LESS,
            BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS);

    @Override
    public String code() {
        return RuleCodes.CHECK_STREAM_COUNT_FOR_EXISTENCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет проверку существования элемента через filter().count()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(BinaryExpr.class).stream()
                .filter(comparison -> COMPARISONS.contains(comparison.getOperator()))
                .filter(comparison -> (isFilteredCount(comparison.getLeft()) && isBound(comparison.getRight()))
                        || (isFilteredCount(comparison.getRight()) && isBound(comparison.getLeft())))
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

    private boolean isBound(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return (value.isIntegerLiteralExpr() || value.isLongLiteralExpr()) && EXISTENCE_BOUNDS.contains(value.toString());
    }
}
