package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Branches;
import ru.akvine.zond.rules.support.ElementTypes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class WaitWithoutTimeoutRule extends AbstractRule {
    private static final String AWAIT = "await";
    private static final String IS_DONE = "isDone";
    private static final String INVOKE_ALL = "invokeAll";
    private static final Set<String> COMPLETED_SOURCES = Set.of("take", "poll");
    private static final Set<String> COMPLETION_SERVICES = Set.of("CompletionService", "ExecutorCompletionService");
    private static final Pattern COMPLETION_NAME = Pattern.compile("(?i).*completion.*");
    private static final Set<String> FUTURE_WAIT_METHODS = Set.of("get", "join");

    private static final Set<String> LATCH_TYPES = Set.of("CountDownLatch", "CyclicBarrier");
    private static final Set<String> FUTURE_TYPES = Set.of(
            "Future", "CompletableFuture", "ScheduledFuture", "FutureTask", "ListenableFuture", "CompletionStage");

    // Методы, которые возвращают Future: executor.submit(task).get()
    private static final Set<String> FUTURE_SOURCES = Set.of("submit", "supplyAsync", "runAsync", "allOf", "anyOf");

    // После allOf(...).join() задачи уже завершены, а orTimeout(...) сам ограничивает ожидание
    private static final String ALL_OF = "allOf";
    private static final String OR_TIMEOUT = "orTimeout";
    private static final String COMPLETE_ON_TIMEOUT = "completeOnTimeout";

    // executor.invokeAll(tasks) ждет все задачи, invokeAny(tasks) - первую успешную; срок - вторым аргументом
    private static final Set<String> INVOKE_METHODS = Set.of("invokeAll", "invokeAny");
    private static final Set<String> EXECUTOR_TYPES = Set.of("ExecutorService", "ScheduledExecutorService",
            "ThreadPoolExecutor", "ScheduledThreadPoolExecutor", "ForkJoinPool", "ListeningExecutorService");
    private static final List<String> EXECUTOR_NAMES = List.of("executor", "pool");

    @Override
    public String code() {
        return RuleCodes.WAIT_WITHOUT_TIMEOUT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ожидание без таймаута: CountDownLatch.await(), Future.get(),"
                + " CompletableFuture.join(), ExecutorService.invokeAll(tasks)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty()) {
                continue;
            }

            Expression scope = Nodes.unwrap(call.getScope().get());
            String method = call.getNameAsString();
            if (INVOKE_METHODS.contains(method) && call.getArguments().size() == 1 && isExecutor(scope)
                    && !isDelegation(scope, call)) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' без таймаута: вызов ждет, пока задачи не завершатся, и если хоть одна"
                                + " зависнет, поток будет ждать вечно; используйте " + method
                                + "(tasks, timeout, unit) - задачи, которые не успели, он отменит"));
            }
            // Тип получателя узнаем только у await(), get() и join(): вызовов без аргументов в коде большинство,
            // а определение типа - дорогая операция
            if (!call.getArguments().isEmpty() || !AWAIT.equals(method) && !FUTURE_WAIT_METHODS.contains(method)) {
                continue;
            }
            Optional<String> type = LocalTypes.typeOf(scope);

            if (AWAIT.equals(method) && type.filter(LATCH_TYPES::contains).isPresent()) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' без таймаута: если счетчик так и не дойдет до нуля, поток будет ждать вечно;"
                                + " используйте await(timeout, unit) и обработайте истечение времени"));
            }

            if (FUTURE_WAIT_METHODS.contains(method) && isFuture(scope, type) && !isCheckedForCompletion(scope, call)
                    && !isDelegation(scope, call) && !isAlreadyComplete(scope, call)) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' без таймаута: если задача зависнет, поток будет ждать вечно;"
                                + " используйте get(timeout, unit) или orTimeout(...)"));
            }
        }
        // futures.stream().map(CompletableFuture::join): то же ожидание, записанное ссылкой на метод
        for (MethodReferenceExpr reference : sourceFile.findAll(MethodReferenceExpr.class)) {
            if (FUTURE_WAIT_METHODS.contains(reference.getIdentifier()) && isFutureType(reference.getScope())
                    && !isWaitLimited(reference)) {
                violations.add(violation(sourceFile, reference,
                        "'" + reference + "' без таймаута: если хоть одна задача зависнет, поток будет ждать вечно;"
                                + " ограничьте ожидание - orTimeout(...) у каждой задачи либо"
                                + " allOf(...).get(timeout, unit) перед сбором результатов"));
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
        // futures.forEach(future -> future.join()) после allOf(futures).get(timeout, unit) уже не ждет
        return type.filter(FUTURE_TYPES::contains).isPresent() && !(isElement(scope) && isWaitLimited(scope));
    }

    // Параметр лямбды или переменная цикла: одна из задач коллекции, которую перебирают
    private boolean isElement(Expression scope) {
        Optional<Node> declaration = LocalTypes.findDeclaration(scope);
        if (declaration.isEmpty()) {
            return false;
        }
        if (declaration.get() instanceof Parameter parameter) {
            return parameter.getParentNode().filter(LambdaExpr.class::isInstance).isPresent();
        }
        return declaration.get() instanceof VariableDeclarator variable && variable.getParentNode()
                .flatMap(Node::getParentNode)
                .filter(ForEachStmt.class::isInstance)
                .isPresent();
    }

    // CompletableFuture::join
    private boolean isFutureType(Expression scope) {
        return scope.isTypeExpr() && scope.asTypeExpr().getType().isClassOrInterfaceType()
                && FUTURE_TYPES.contains(scope.asTypeExpr().getType().asClassOrInterfaceType().getNameAsString());
    }

    private boolean isWaitLimited(Node usage) {
        List<MethodCallExpr> calls = Nodes.enclosingCallable(usage)
                .map(callable -> callable.findAll(MethodCallExpr.class))
                .orElse(List.of());
        boolean released = calls.stream().anyMatch(this::releasesCombined);
        return calls.stream().anyMatch(call -> switch (call.getNameAsString()) {
            case OR_TIMEOUT -> true;
            case ALL_OF -> !released;
            case COMPLETE_ON_TIMEOUT -> !releasesCombined(call);
            default -> false;
        });
    }

    /**
     * allOf(futures).completeOnTimeout(null, 5, SECONDS).join() по таймауту вернет управление, а сами задачи
     * при этом могут еще работать: ожидание каждой из них такой allOf не ограничивает
     */
    private boolean releasesCombined(MethodCallExpr call) {
        if (!COMPLETE_ON_TIMEOUT.equals(call.getNameAsString()) || call.getScope().isEmpty()) {
            return false;
        }
        Expression combined = Nodes.unwrap(call.getScope().get());
        Expression source = LocalTypes.findInitializer(combined).map(Nodes::unwrap).orElse(combined);
        return source.isMethodCallExpr() && ALL_OF.equals(source.asMethodCallExpr().getNameAsString());
    }

    private boolean isExecutor(Expression scope) {
        String name = MethodCalls.receiverName(scope).toLowerCase();
        return LocalTypes.isAnyOf(scope, EXECUTOR_TYPES,
                () -> EXECUTOR_NAMES.stream().anyMatch(name::contains));
    }

    // public V get() { return future.get(); } у обертки над Future, static <T> T join(CompletableFuture<T> future)
    // { return future.join(); }: метод сам и есть ожидание без предела, предел задает тот, кто его вызывает
    private boolean isDelegation(Expression scope, MethodCallExpr call) {
        return Nodes.enclosingCallable(call)
                .filter(callable -> callable instanceof MethodDeclaration method
                        && method.getNameAsString().equals(call.getNameAsString())
                        && (method.getParameters().isEmpty() || !call.getArguments().isEmpty()
                        || method.getParameters().stream()
                        .anyMatch(parameter -> parameter.getNameAsString().equals(scope.toString()))))
                .isPresent();
    }

    /**
     * Задача к этому месту уже завершена, и get() вернет результат сразу: ее отдал
     * {@code completionService.take()}, она из списка, который вернул {@code invokeAll}, либо ее уже дождались
     * строкой выше
     */
    private boolean isAlreadyComplete(Expression scope, MethodCallExpr call) {
        if (isCompletedSource(scope)) {
            return true;
        }
        if (!scope.isNameExpr()) {
            return false;
        }
        String future = scope.asNameExpr().getNameAsString();
        Optional<Node> callable = Nodes.enclosingCallable(call);
        if (LocalTypes.findInitializer(scope).map(Nodes::unwrap).filter(this::isCompletedSource).isPresent()
                || callable.isPresent() && callable.get().findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().toString().equals(future)
                        && isCompletedSource(Nodes.unwrap(assignment.getValue())))) {
            return true;
        }
        // for (Future<T> done : executor.invokeAll(tasks)) done.get()
        boolean fromInvokeAll = LocalTypes.findDeclaration(scope)
                .flatMap(ElementTypes::collectionOf)
                .map(Nodes::unwrap)
                .map(collection -> collection.isNameExpr()
                        ? LocalTypes.findInitializer(collection).map(Nodes::unwrap).orElse(collection)
                        : collection)
                .filter(collection -> collection.isMethodCallExpr()
                        && INVOKE_ALL.equals(collection.asMethodCallExpr().getNameAsString()))
                .isPresent();
        if (fromInvokeAll) {
            return true;
        }
        // profile.join(); ... profile.join(): второе ожидание той же задачи ничего не ждет
        return callable.isPresent() && callable.get().findAll(MethodCallExpr.class).stream()
                .filter(earlier -> earlier != call && earlier.getArguments().isEmpty()
                        && FUTURE_WAIT_METHODS.contains(earlier.getNameAsString()))
                .filter(earlier -> earlier.getScope().filter(waited -> waited.toString().equals(future)).isPresent())
                .filter(earlier -> earlier.getBegin().isPresent() && call.getBegin().isPresent()
                        && earlier.getBegin().get().isBefore(call.getBegin().get()))
                .anyMatch(earlier -> Nodes.enclosingCallable(earlier).orElse(null) == callable.get()
                        && !Branches.areExclusive(earlier, call) && !isInsideConditional(earlier, call));
    }

    // completionService.take(), completionService.poll(1, SECONDS): очередь отдает только завершенные задачи
    private boolean isCompletedSource(Expression source) {
        return source.isMethodCallExpr() && COMPLETED_SOURCES.contains(source.asMethodCallExpr().getNameAsString())
                && source.asMethodCallExpr().getScope()
                .filter(queue -> LocalTypes.isAnyOf(queue, COMPLETION_SERVICES,
                        () -> COMPLETION_NAME.matcher(MethodCalls.receiverName(queue)).matches()))
                .isPresent();
    }

    // Первое ожидание стоит под условием, в которое второе не входит: до второго можно дойти и без первого
    private boolean isInsideConditional(MethodCallExpr earlier, MethodCallExpr later) {
        for (Node current = earlier.getParentNode().orElse(null); current != null && !Nodes.isInside(later, current);
             current = current.getParentNode().orElse(null)) {
            if (current instanceof IfStmt || current instanceof ConditionalExpr || current instanceof SwitchEntry
                    || current instanceof CatchClause || current instanceof LambdaExpr) {
                return true;
            }
        }
        return false;
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
