package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Цепочки вызовов вида source.stream().map(...).filter(...).collect(...)
 */
@UtilityClass
public class StreamChains {
    private final static Set<String> STREAM_TYPES = Set.of("Stream", "IntStream", "LongStream", "DoubleStream");
    private final static String GENERATE = "generate";
    private final static String ITERATE = "iterate";

    // iterate(seed, next) бесконечен, iterate(seed, hasNext, next) - нет
    private final static int INFINITE_ITERATE_ARGUMENTS = 2;

    // Операции, которые делают бесконечный стрим конечным
    private final static Set<String> LIMITING_OPERATIONS = Set.of("limit", "takeWhile");

    /**
     * @return вызовы, которые идут в цепочке после данного: для stream() в a.stream().map(f).toList() это map и toList
     */
    public List<MethodCallExpr> callsAfter(MethodCallExpr call) {
        List<MethodCallExpr> calls = new ArrayList<>();
        MethodCallExpr current = call;
        while (true) {
            MethodCallExpr scope = current;
            Optional<MethodCallExpr> next = current.getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr)
                    .map(parent -> (MethodCallExpr) parent)
                    .filter(parent -> parent.getScope().filter(expression -> expression == scope).isPresent());
            if (next.isEmpty()) {
                return calls;
            }
            calls.add(next.get());
            current = next.get();
        }
    }

    /**
     * @return вызовы, которые идут в цепочке до данного: для toList() в a.stream().map(f).toList() это map и stream
     */
    public List<MethodCallExpr> callsBefore(MethodCallExpr call) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Optional<Expression> scope = call.getScope();
        while (scope.isPresent() && Nodes.unwrap(scope.get()).isMethodCallExpr()) {
            MethodCallExpr previous = Nodes.unwrap(scope.get()).asMethodCallExpr();
            calls.add(previous);
            scope = previous.getScope();
        }
        return calls;
    }

    // Stream.generate(...), Stream.iterate(seed, next) и то же для IntStream / LongStream / DoubleStream
    public boolean isInfiniteSource(MethodCallExpr call) {
        boolean onStream = call.getScope()
                .filter(scope -> STREAM_TYPES.stream().anyMatch(type -> MethodCalls.isType(scope, type)))
                .isPresent();
        if (!onStream) {
            return false;
        }
        return GENERATE.equals(call.getNameAsString())
                || (ITERATE.equals(call.getNameAsString())
                && call.getArguments().size() == INFINITE_ITERATE_ARGUMENTS);
    }

    public boolean isLimiting(MethodCallExpr call) {
        return LIMITING_OPERATIONS.contains(call.getNameAsString());
    }

    /**
     * @return true, если выражение - цепочка от бесконечного источника, нигде не ограниченная limit / takeWhile
     */
    public boolean isUnbounded(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isMethodCallExpr()) {
            return false;
        }

        List<MethodCallExpr> chain = new ArrayList<>();
        chain.add(value.asMethodCallExpr());
        chain.addAll(callsBefore(value.asMethodCallExpr()));
        return chain.stream().anyMatch(StreamChains::isInfiniteSource)
                && chain.stream().noneMatch(StreamChains::isLimiting);
    }
}
