package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Максимум, минимум или сумма пересчитываются в цикле с нуля.
 * <p>
 * {@code Collections.max(values.subList(0, i + 1))} на каждом шаге заново перебирает все элементы до
 * текущего - на N элементах это N²/2 сравнений вместо N. Тот же результат дает переменная, которую
 * обновляют на каждом шаге: {@code max = Math.max(max, value)}. А если коллекция в цикле не меняется,
 * значение достаточно вычислить один раз перед циклом.
 */
@Component
public class AggregateRecomputedInLoopRule extends AbstractRule {
    private static final String COLLECTIONS = "Collections";
    private static final Set<String> COLLECTION_AGGREGATES = Set.of("max", "min");
    private static final Set<String> STREAM_AGGREGATES =
            Set.of("max", "min", "sum", "average", "count", "reduce", "summaryStatistics");
    private static final String SUB_LIST = "subList";
    private static final String STREAM = "stream";
    private static final Set<String> ADDERS = Set.of("add", "addAll", "addFirst", "addLast", "offer", "push");

    private enum Source {SLICE, GROWING, UNCHANGED}

    private record Found(Source source, String collection) {
    }

    @Override
    public String code() {
        return RuleCodes.AGGREGATE_RECOMPUTED_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет максимум, минимум и сумму, которые в цикле пересчитываются с нуля"
                + " вместо обновления на каждом шаге";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (TestClasses.isInside(call)) {
                continue;
            }
            aggregated(call)
                    .flatMap(collection -> classify(call, collection))
                    .ifPresent(found -> violations.add(violation(sourceFile, call.getName(), message(call, found))));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Collections.max(x), Collections.min(x, comparator); x.subList(...).stream()...max()
    private Optional<Expression> aggregated(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = Nodes.unwrap(call.getScope().get());
        String name = call.getNameAsString();
        if (COLLECTION_AGGREGATES.contains(name) && scope.isNameExpr() && COLLECTIONS.equals(scope.toString())
                && !call.getArguments().isEmpty()) {
            return Optional.of(Nodes.unwrap(call.getArgument(0)));
        }
        // О стриме прямо по коллекции (values.stream().max()) сообщает jr:75; здесь - стрим по срезу
        return STREAM_AGGREGATES.contains(name) ? streamSource(scope).filter(this::isSlice) : Optional.empty();
    }

    // От конца цепочки стрима к ее началу: values.subList(0, i).stream().mapToInt(...).max()
    private Optional<Expression> streamSource(Expression chain) {
        Expression current = chain;
        while (current.isMethodCallExpr()) {
            MethodCallExpr call = current.asMethodCallExpr();
            if (STREAM.equals(call.getNameAsString()) && call.getArguments().isEmpty()) {
                return call.getScope().map(Nodes::unwrap);
            }
            if (call.getScope().isEmpty()) {
                return Optional.empty();
            }
            current = Nodes.unwrap(call.getScope().get());
        }
        return Optional.empty();
    }

    private boolean isSlice(Expression expression) {
        return expression.isMethodCallExpr() && SUB_LIST.equals(expression.asMethodCallExpr().getNameAsString());
    }

    private Optional<Found> classify(MethodCallExpr call, Expression collection) {
        boolean slice = isSlice(collection);
        Expression base = slice
                ? collection.asMethodCallExpr().getScope().map(Nodes::unwrap).orElse(collection)
                : collection;
        // Коллекция объявлена до цикла: на каждой итерации перебирается она же
        Optional<Node> loop = LocalTypes.findDeclaration(base)
                .flatMap(declaration -> CodeContexts.loopOverOuter(call, declaration));
        if (loop.isEmpty()) {
            return Optional.empty();
        }
        String name = base.toString();
        if (slice) {
            return Optional.of(new Found(Source.SLICE, name));
        }
        return Optional.of(new Found(isFilledIn(loop.get(), name) ? Source.GROWING : Source.UNCHANGED, name));
    }

    private boolean isFilledIn(Node loop, String collection) {
        return loop.findAll(MethodCallExpr.class).stream()
                .filter(call -> ADDERS.contains(call.getNameAsString()))
                .anyMatch(call -> call.getScope().map(Expression::toString).filter(collection::equals).isPresent());
    }

    private String message(MethodCallExpr call, Found found) {
        String shown = "'" + Nodes.text(call) + "'";
        String running = "ведите значение на лету - заведите переменную перед циклом и обновляйте ее на каждом"
                + " шаге (Math.max, Math.min, +=)";
        return switch (found.source()) {
            case SLICE -> shown + " пересчитывается на каждой итерации по срезу списка '" + found.collection()
                    + "': элементы всякий раз перебираются заново, вместе с циклом выходит O(N²); " + running;
            case GROWING -> shown + " пересчитывается на каждой итерации по коллекции '" + found.collection()
                    + "', которая пополняется в этом же цикле: каждый раз она перебирается целиком, в сумме"
                    + " O(N²); " + running;
            case UNCHANGED -> shown + " вычисляется заново на каждой итерации, хотя '" + found.collection()
                    + "' в цикле не меняется: это полный перебор на каждый шаг, O(N×M); вычислите значение один"
                    + " раз перед циклом";
        };
    }
}
