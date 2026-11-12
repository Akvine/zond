package ru.akvine.zond.rules.performance;

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
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * orderRepository.countByClientId(id) > 0: чтобы ответить "есть ли хоть одна", база пересчитывает все подходящие
 * строки. existsByClientId(id) останавливается на первой.
 */
@Component
public class RepositoryCountForExistenceRule extends AbstractRule {
    private static final Pattern LONG_SUFFIX = Pattern.compile("[lL]$");
    private static final String COUNT_PREFIX = "count";
    private static final String ZERO = "0";
    private static final String ONE = "1";
    // count > 0, count != 0, count == 0, count <= 0
    private static final Set<BinaryExpr.Operator> ZERO_CHECKS = Set.of(
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.EQUALS,
            BinaryExpr.Operator.LESS_EQUALS);
    // count >= 1, count < 1. Сравнения "ровно один" и "больше одного" первой строкой не проверить
    private static final Set<BinaryExpr.Operator> ONE_CHECKS = Set.of(
            BinaryExpr.Operator.GREATER_EQUALS, BinaryExpr.Operator.LESS);
    private static final Map<BinaryExpr.Operator, BinaryExpr.Operator> MIRRORED = Map.of(
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.LESS,
            BinaryExpr.Operator.LESS, BinaryExpr.Operator.GREATER,
            BinaryExpr.Operator.GREATER_EQUALS, BinaryExpr.Operator.LESS_EQUALS,
            BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER_EQUALS);

    @Override
    public String code() {
        return RuleCodes.REPOSITORY_COUNT_FOR_EXISTENCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет проверку существования записи через count-запрос репозитория вместо existsBy";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr comparison : sourceFile.unit().findAll(BinaryExpr.class)) {
            BinaryExpr.Operator operator = comparison.getOperator();
            boolean existence = isCountQuery(comparison.getLeft()) && isExistenceBound(operator, comparison.getRight())
                    || isCountQuery(comparison.getRight())
                    && isExistenceBound(MIRRORED.getOrDefault(operator, operator), comparison.getLeft());
            if (existence && !TestClasses.isInside(comparison)) {
                violations.add(violation(sourceFile, comparison,
                        "'" + Nodes.text(comparison) + "': ради ответа \"есть ли хоть одна запись\" база пересчитывает"
                                + " все подходящие строки; объявите в репозитории метод existsBy... - он"
                                + " останавливается на первой"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // repository.count(), repository.countByStatus(status), repository.countAllByOwner(owner)
    private boolean isCountQuery(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value.isMethodCallExpr() && value.asMethodCallExpr().getNameAsString().startsWith(COUNT_PREFIX)
                && Repositories.isRepositoryCall(value.asMethodCallExpr());
    }

    // Сравнение записано так, будто count стоит слева
    private boolean isExistenceBound(BinaryExpr.Operator operator, Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isIntegerLiteralExpr() && !value.isLongLiteralExpr()) {
            return false;
        }
        String bound = LONG_SUFFIX.matcher(value.toString()).replaceAll("");
        return ZERO.equals(bound) && ZERO_CHECKS.contains(operator) || ONE.equals(bound) && ONE_CHECKS.contains(operator);
    }
}
