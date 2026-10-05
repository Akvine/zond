package ru.akvine.zond.rules.concurrency;

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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class WaitWithoutTimeoutRule extends AbstractRule {
    private static final String AWAIT = "await";
    private static final String IS_DONE = "isDone";
    private static final Set<String> FUTURE_WAIT_METHODS = Set.of("get", "join");

    private static final Set<String> LATCH_TYPES = Set.of("CountDownLatch", "CyclicBarrier");
    private static final Set<String> FUTURE_TYPES = Set.of(
            "Future", "CompletableFuture", "ScheduledFuture", "FutureTask", "ListenableFuture", "CompletionStage");

    // Методы, которые возвращают Future: executor.submit(task).get()
    private static final Set<String> FUTURE_SOURCES = Set.of("submit", "supplyAsync", "runAsync", "allOf", "anyOf");

    @Override
    public String code() {
        return RuleCodes.WAIT_WITHOUT_TIMEOUT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ожидание без таймаута: CountDownLatch.await(), Future.get(),"
                + " CompletableFuture.join()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!call.getArguments().isEmpty() || call.getScope().isEmpty()) {
                continue;
            }

            Expression scope = Nodes.unwrap(call.getScope().get());
            String method = call.getNameAsString();
            Optional<String> type = LocalTypes.typeOf(scope);

            if (AWAIT.equals(method) && type.filter(LATCH_TYPES::contains).isPresent()) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' без таймаута: если счетчик так и не дойдет до нуля, поток будет ждать вечно;"
                                + " используйте await(timeout, unit) и обработайте истечение времени"));
            }

            if (FUTURE_WAIT_METHODS.contains(method) && isFuture(scope, type) && !isCheckedForCompletion(scope, call)) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' без таймаута: если задача зависнет, поток будет ждать вечно;"
                                + " используйте get(timeout, unit) или orTimeout(...)"));
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
        return ErrorType.CONCURRENCY;
    }

    private boolean isFuture(Expression scope, Optional<String> type) {
        if (scope.isMethodCallExpr()) {
            return FUTURE_SOURCES.contains(scope.asMethodCallExpr().getNameAsString());
        }
        return type.filter(FUTURE_TYPES::contains).isPresent();
    }

    // if (future.isDone()) future.get() - результат уже готов, ожидания не будет
    private boolean isCheckedForCompletion(Expression scope, MethodCallExpr call) {
        String future = scope.toString();
        Optional<Node> callable = Nodes.enclosingCallable(call);
        return callable.isPresent() && callable.get().findAll(MethodCallExpr.class).stream()
                .filter(check -> IS_DONE.equals(check.getNameAsString()))
                .anyMatch(check -> check.getScope()
                        .filter(checked -> Nodes.unwrap(checked).toString().equals(future))
                        .isPresent());
    }
}
