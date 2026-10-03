package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import lombok.experimental.UtilityClass;

import java.util.Set;

@UtilityClass
class ExecutorCreations {
    private final static String EXECUTORS = "Executors";
    private final static String FACTORY_PREFIX = "new";

    // Типы не разрешаем, поэтому пулы потоков узнаем по известным именам классов
    private final static Set<String> EXECUTOR_TYPES = Set.of(
            "ThreadPoolExecutor", "ScheduledThreadPoolExecutor", "ForkJoinPool", "ThreadPoolTaskExecutor",
            "ThreadPoolTaskScheduler");

    /**
     * @return true, если выражение создает пул потоков: Executors.newFixedThreadPool(...), new ThreadPoolExecutor(...)
     */
    boolean creates(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isObjectCreationExpr()) {
            return EXECUTOR_TYPES.contains(value.asObjectCreationExpr().getType().getNameAsString());
        }
        return value.isMethodCallExpr()
                && value.asMethodCallExpr().getNameAsString().startsWith(FACTORY_PREFIX)
                && value.asMethodCallExpr().getScope().filter(scope -> MethodCalls.isType(scope, EXECUTORS)).isPresent();
    }

    /**
     * @return как назвать создание пула в сообщении: Executors.newFixedThreadPool(...), new ThreadPoolExecutor(...)
     */
    String describe(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isObjectCreationExpr()) {
            return "new " + value.asObjectCreationExpr().getType().getNameAsString() + "(...)";
        }
        return EXECUTORS + "." + value.asMethodCallExpr().getNameAsString() + "(...)";
    }
}
