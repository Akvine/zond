package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.experimental.UtilityClass;

import java.util.Set;

@UtilityClass
class ExecutorCreations {
    private final static String EXECUTORS = "Executors";
    private final static String FACTORY_PREFIX = "new";

    // Пулы потоков: известные классы и их наследники; собственный класс проекта с таким же именем - не пул
    private final static Set<String> EXECUTOR_TYPES = Set.of(
            "ThreadPoolExecutor", "ScheduledThreadPoolExecutor", "ForkJoinPool", "ThreadPoolTaskExecutor",
            "ThreadPoolTaskScheduler");

    /**
     * @return true, если выражение создает пул потоков: Executors.newFixedThreadPool(...), new ThreadPoolExecutor(...)
     */
    boolean creates(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isObjectCreationExpr()) {
            ClassOrInterfaceType type = value.asObjectCreationExpr().getType();
            return Types.isKindOf(type, EXECUTOR_TYPES).orElseGet(() -> EXECUTOR_TYPES.contains(type.getNameAsString()));
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
