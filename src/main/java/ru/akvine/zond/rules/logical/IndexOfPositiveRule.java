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
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Set;

@Component
public class IndexOfPositiveRule extends AbstractRule {
    private static final Set<String> INDEX_METHODS = Set.of("indexOf", "lastIndexOf");
    private static final String ZERO = "0";

    @Override
    public String code() {
        return RuleCodes.INDEX_OF_POSITIVE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сравнение indexOf(...) > 0 вместо >= 0";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(BinaryExpr.class).stream()
                .filter(this::skipsFirstPosition)
                .map(comparison -> violation(sourceFile, comparison,
                        "'" + comparison + "': совпадение в самом начале имеет индекс 0, и это условие его"
                                + " пропустит; \"не найдено\" - это -1, сравнивайте через >= 0 либо"
                                + " используйте contains(...)"))
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

    // x.indexOf(y) > 0 либо 0 < x.indexOf(y)
    private boolean skipsFirstPosition(BinaryExpr comparison) {
        if (comparison.getOperator() == BinaryExpr.Operator.GREATER) {
            return isIndexOf(comparison.getLeft()) && isZero(comparison.getRight());
        }
        return comparison.getOperator() == BinaryExpr.Operator.LESS
                && isZero(comparison.getLeft()) && isIndexOf(comparison.getRight());
    }

    private boolean isIndexOf(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value.isMethodCallExpr() && INDEX_METHODS.contains(value.asMethodCallExpr().getNameAsString());
    }

    private boolean isZero(Expression expression) {
        return ZERO.equals(Nodes.unwrap(expression).toString());
    }
}
