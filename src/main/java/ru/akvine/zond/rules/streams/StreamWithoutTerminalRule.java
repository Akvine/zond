package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
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
import ru.akvine.zond.rules.support.StreamChains;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Стрим без завершающей операции. Промежуточные операции (map, filter, peek) ленивы: они только описывают
 * конвейер, а выполняет его завершающая (forEach, collect, count). Цепочка, которая стоит отдельным оператором
 * и кончается промежуточной операцией, не делает ничего.
 */
@Component
public class StreamWithoutTerminalRule extends AbstractRule {
    private static final Set<String> INTERMEDIATE = Set.of(
            "filter", "map", "flatMap", "mapToInt", "mapToLong", "mapToDouble", "mapToObj", "mapMulti", "peek",
            "sorted", "distinct", "limit", "skip", "boxed", "parallel", "sequential", "unordered", "takeWhile",
            "dropWhile", "asLongStream", "asDoubleStream");
    // Операции, которые результат своей лямбды не читают
    private static final Set<String> DISCARDING = Set.of("forEach", "forEachOrdered", "forEachRemaining", "ifPresent");
    private static final Set<String> STREAM_SOURCES = Set.of("stream", "parallelStream", "chars", "lines");
    private static final Set<String> STREAM_TYPES = Set.of("Stream", "IntStream", "LongStream", "DoubleStream");
    // Arrays.stream(...) уже учтен как stream; остальное - фабрики самих стримов
    private static final Set<String> STREAM_FACTORIES = Set.of(
            "of", "ofNullable", "iterate", "generate", "range", "rangeClosed", "concat");

    @Override
    public String code() {
        return RuleCodes.STREAM_WITHOUT_TERMINAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет стримы без завершающей операции: цепочка из map, filter, peek отдельным"
                + " оператором не выполняется";
    }

    // Что конвейер не запустится, видно по самому коду
    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ExpressionStmt statement : sourceFile.unit().findAll(ExpressionStmt.class)) {
            Expression expression = Nodes.unwrap(statement.getExpression());
            if (!expression.isMethodCallExpr() || !INTERMEDIATE.contains(expression.asMethodCallExpr().getNameAsString())
                    || isReturnedFromLambda(statement)) {
                continue;
            }
            MethodCallExpr last = expression.asMethodCallExpr();
            if (isStream(last)) {
                violations.add(violation(sourceFile, last.getName(),
                        "Стрим без завершающей операции: цепочка кончается на '" + last.getNameAsString()
                                + "(...)', а результат никто не читает. Промежуточные операции ленивы - они только"
                                + " описывают конвейер, и ни один элемент через него не пройдет; добавьте"
                                + " завершающую операцию (forEach, collect, toList, count) либо уберите строку"));
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
        return ErrorType.STREAM;
    }

    /**
     * Тело лямбды-выражения парсер тоже отдает оператором: orders.stream().flatMap(order -> order.items().stream()
     * .map(...)) возвращает стрим наружу. Результат пропадает только там, где его никто не ждет: forEach, ifPresent
     */
    private boolean isReturnedFromLambda(ExpressionStmt statement) {
        Optional<LambdaExpr> lambda = statement.getParentNode()
                .filter(parent -> parent instanceof LambdaExpr)
                .map(parent -> (LambdaExpr) parent);
        boolean discarded = lambda.flatMap(LambdaExpr::getParentNode)
                .filter(owner -> owner instanceof MethodCallExpr call && DISCARDING.contains(call.getNameAsString()))
                .isPresent();
        return lambda.isPresent() && !discarded;
    }

    // У построителей запросов тоже есть limit() и map(): стримом цепочку делает только ее источник
    private boolean isStream(MethodCallExpr last) {
        List<MethodCallExpr> before = StreamChains.callsBefore(last);
        boolean fromSource = before.stream().anyMatch(step -> STREAM_SOURCES.contains(step.getNameAsString())
                || STREAM_FACTORIES.contains(step.getNameAsString())
                && step.getScope().filter(scope -> STREAM_TYPES.contains(scope.toString())).isPresent());
        Optional<Expression> root = before.isEmpty() ? last.getScope() : before.get(before.size() - 1).getScope();
        return fromSource || root.flatMap(LocalTypes::typeOf).filter(STREAM_TYPES::contains).isPresent();
    }
}
