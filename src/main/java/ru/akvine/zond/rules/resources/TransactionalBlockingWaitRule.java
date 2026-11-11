package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractTransactionalBlockingCallRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Thread.sleep(...), future.get() и latch.await() внутри @Transactional-метода: пока поток ждет, транзакция
 * открыта и держит соединение с базой и взятые блокировки строк.
 */
@Component
public class TransactionalBlockingWaitRule extends AbstractTransactionalBlockingCallRule {
    private static final String SLEEP = "sleep";
    private static final String AWAIT = "await";
    private static final Set<String> SLEEPERS = Set.of("Thread", "TimeUnit");
    private static final Pattern TIME_UNIT = Pattern.compile(
            "(TimeUnit\\.)?(NANO|MICRO|MILLI)?SECONDS|(TimeUnit\\.)?MINUTES");
    private static final Set<String> FUTURE_WAITS = Set.of("get", "join");
    private static final Set<String> FUTURE_TYPES = Set.of(
            "Future", "CompletableFuture", "ScheduledFuture", "ListenableFuture", "FutureTask");
    private static final Pattern FUTURE_NAME = Pattern.compile("(?i).*(future|promise)s?");
    private static final Set<String> ASYNC_STARTS = Set.of("supplyAsync", "runAsync", "submit", "allOf", "anyOf");
    private static final Set<String> LATCH_TYPES = Set.of("CountDownLatch", "CyclicBarrier", "Phaser");
    private static final Pattern LATCH_NAME = Pattern.compile("(?i).*(latch|barrier)");

    @Override
    public String code() {
        return RuleCodes.TRANSACTIONAL_BLOCKING_WAIT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ожидание внутри @Transactional-методов: Thread.sleep, Future.get,"
                + " CompletableFuture.join, CountDownLatch.await";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    @Override
    protected Optional<String> describeBlockingCall(Node node) {
        if (!(node instanceof MethodCallExpr call) || call.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = Nodes.unwrap(call.getScope().get());
        return waits(call, scope) && isFirstWait(call)
                ? Optional.of(MethodCalls.receiverName(scope) + "." + call.getNameAsString())
                : Optional.empty();
    }

    private boolean waits(MethodCallExpr call, Expression scope) {
        String name = call.getNameAsString();
        if (SLEEP.equals(name)) {
            return isSleeper(scope);
        }
        // map.get(key) - не ожидание: у Future.get аргументов нет либо их два, таймаут и единица времени
        if (FUTURE_WAITS.contains(name)) {
            return call.getArguments().size() != 1 && isFuture(scope);
        }
        return AWAIT.equals(name) && isOf(scope, LATCH_TYPES, LATCH_NAME);
    }

    @Override
    protected String message(String method, String call) {
        return "Ожидание '" + call + "' внутри @Transactional-метода '" + method + "': пока поток ждет, транзакция"
                + " открыта и держит соединение с БД и блокировки строк, под нагрузкой пул соединений исчерпается;"
                + " вынесите ожидание за пределы транзакции";
    }

    // Повторный future.join() той же задачи уже ничего не ждет: результат готов
    private boolean isFirstWait(MethodCallExpr call) {
        return call.findAncestor(CallableDeclaration.class)
                .flatMap(callable -> callable.findAll(MethodCallExpr.class).stream()
                        .filter(other -> other.toString().equals(call.toString()))
                        .findFirst())
                .filter(first -> first == call)
                .isPresent();
    }

    // Thread.sleep(ms), TimeUnit.SECONDS.sleep(1), SECONDS.sleep(1)
    private boolean isSleeper(Expression scope) {
        return scope.isNameExpr() && SLEEPERS.contains(scope.asNameExpr().getNameAsString())
                || TIME_UNIT.matcher(scope.toString()).matches();
    }

    // future.get(), executor.submit(task).get(), CompletableFuture.allOf(tasks).join()
    private boolean isFuture(Expression scope) {
        return scope.isMethodCallExpr() && ASYNC_STARTS.contains(scope.asMethodCallExpr().getNameAsString())
                || isOf(scope, FUTURE_TYPES, FUTURE_NAME);
    }

    private boolean isOf(Expression scope, Set<String> types, Pattern name) {
        Optional<String> type = LocalTypes.typeOf(scope);
        return type.isPresent()
                ? types.contains(type.get())
                : scope.isNameExpr() && name.matcher(scope.asNameExpr().getNameAsString()).matches();
    }
}
