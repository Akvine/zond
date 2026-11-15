package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.WildcardType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
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

    // futures.values().stream().filter(...).forEach(future -> future.join()): элемент остается тем же
    private static final Set<String> SAME_ELEMENT_METHODS = Set.of(
            "stream", "parallelStream", "values", "filter", "peek", "sorted", "distinct", "limit", "skip");
    // После allOf(...).join() задачи уже завершены, а orTimeout(...) сам ограничивает ожидание
    private static final Set<String> WAIT_LIMITS = Set.of("allOf", "orTimeout", "completeOnTimeout");

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
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
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
            if (!call.getArguments().isEmpty()) {
                continue;
            }
            Optional<String> type = LocalTypes.typeOf(scope);

            if (AWAIT.equals(method) && type.filter(LATCH_TYPES::contains).isPresent()) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' без таймаута: если счетчик так и не дойдет до нуля, поток будет ждать вечно;"
                                + " используйте await(timeout, unit) и обработайте истечение времени"));
            }

            if (FUTURE_WAIT_METHODS.contains(method) && isFuture(scope, type) && !isCheckedForCompletion(scope, call)
                    && !isDelegation(scope, call)) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' без таймаута: если задача зависнет, поток будет ждать вечно;"
                                + " используйте get(timeout, unit) или orTimeout(...)"));
            }
        }
        // futures.stream().map(CompletableFuture::join): то же ожидание, записанное ссылкой на метод
        for (MethodReferenceExpr reference : sourceFile.unit().findAll(MethodReferenceExpr.class)) {
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
        return type.filter(FUTURE_TYPES::contains).isPresent()
                || type.isEmpty() && isElementOfFutures(scope) && !isWaitLimited(scope);
    }

    /**
     * futures.forEach((index, future) -> future.join()), for (var future : futures) future.get(): у параметра
     * лямбды и переменной var типа в коде нет, но он виден по коллекции, которую перебирают
     */
    private boolean isElementOfFutures(Expression scope) {
        if (!scope.isNameExpr()) {
            return false;
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(scope);
        if (declaration.isEmpty()) {
            return false;
        }
        Optional<Expression> source = Optional.empty();
        if (declaration.get() instanceof Parameter parameter && parameter.getType().isUnknownType()) {
            source = parameter.getParentNode()
                    .filter(LambdaExpr.class::isInstance)
                    .flatMap(Node::getParentNode)
                    .filter(MethodCallExpr.class::isInstance)
                    .flatMap(iteration -> ((MethodCallExpr) iteration).getScope());
        } else if (declaration.get() instanceof VariableDeclarator variable && variable.getType().isVarType()) {
            source = variable.getParentNode()
                    .flatMap(Node::getParentNode)
                    .filter(ForEachStmt.class::isInstance)
                    .map(loop -> ((ForEachStmt) loop).getIterable());
        }
        return source.map(this::collectionOf).filter(this::holdsFutures).isPresent();
    }

    // futures.values().stream() -> futures
    private Expression collectionOf(Expression source) {
        Expression current = Nodes.unwrap(source);
        while (current.isMethodCallExpr() && current.asMethodCallExpr().getScope().isPresent()
                && SAME_ELEMENT_METHODS.contains(current.asMethodCallExpr().getNameAsString())) {
            current = Nodes.unwrap(current.asMethodCallExpr().getScope().get());
        }
        return current;
    }

    // List<Future<Report>>, Map<Integer, CompletableFuture<List<?>>>, Future<?>[]
    private boolean holdsFutures(Expression collection) {
        Optional<Type> declared = LocalTypes.findDeclaration(collection)
                .map(declaration -> declaration instanceof Parameter parameter
                        ? parameter.getType()
                        : declaration instanceof VariableDeclarator variable ? variable.getType() : null);
        if (declared.isEmpty()) {
            return false;
        }
        if (declared.get().isArrayType()) {
            return isFutureType(declared.get().asArrayType().getComponentType());
        }
        return declared.get().isClassOrInterfaceType()
                && declared.get().asClassOrInterfaceType().getTypeArguments().stream()
                .flatMap(List::stream)
                .anyMatch(this::isFutureType);
    }

    private boolean isFutureType(Node type) {
        if (type instanceof TypeExpr expression) {
            return isFutureType(expression.getType());
        }
        if (type instanceof WildcardType wildcard) {
            return wildcard.getExtendedType().filter(this::isFutureType).isPresent();
        }
        return type instanceof ClassOrInterfaceType named && FUTURE_TYPES.contains(named.getNameAsString());
    }

    private boolean isWaitLimited(Node usage) {
        return Nodes.enclosingCallable(usage)
                .filter(callable -> callable.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> WAIT_LIMITS.contains(call.getNameAsString())))
                .isPresent();
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
