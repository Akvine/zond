package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Остановка пула потоков, при которой теряются задачи. Плавная остановка состоит из трех шагов:
 * {@code shutdown()} - новых задач не принимать, {@code awaitTermination(...)} - дождаться тех, что уже идут,
 * {@code shutdownNow()} - прервать оставшиеся, если время вышло либо само ожидание прервано; при
 * InterruptedException еще и восстановить флаг прерывания.
 */
@Component
public class ExecutorGracefulShutdownRule extends AbstractRule {
    private static final String SHUTDOWN = "shutdown";
    private static final String SHUTDOWN_NOW = "shutdownNow";
    private static final String AWAIT_TERMINATION = "awaitTermination";
    private static final String INTERRUPT = "interrupt";
    private static final String MAIN = "main";
    private static final String PRE_DESTROY = "PreDestroy";

    // Пулы JDK и Guava. У пулов Spring (ThreadPoolTaskExecutor) shutdown() ждет задачи сам - по своим настройкам
    private static final Set<String> EXECUTOR_TYPES = Set.of(
            "ExecutorService", "ScheduledExecutorService", "ThreadPoolExecutor", "ScheduledThreadPoolExecutor",
            "ForkJoinPool", "ListeningExecutorService", "ListeningScheduledExecutorService");
    // Тип определить не удалось: пул выдает имя - executor, workerPool, getExecutorService()
    private static final Pattern EXECUTOR_NAME = Pattern.compile(".*(executor|threadpool|pool)(service)?$", Pattern.CASE_INSENSITIVE);

    // Ожидание другим способом: close() ждет задачи сам, isTerminated() опрашивают в цикле
    private static final Set<String> OTHER_WAITS = Set.of("close", "isTerminated");
    // Результаты задач получены до остановки: ждать больше нечего
    private static final Set<String> TASK_WAITS = Set.of("invokeAll", "invokeAny", "join", "allOf", "await", "awaitQuiescence");
    private static final String GET = "get";
    private static final Set<String> FUTURE_TYPES = Set.of(
            "Future", "CompletableFuture", "ScheduledFuture", "FutureTask", "ListenableFuture", "CompletionStage");
    private static final Pattern FUTURE_NAME = Pattern.compile(".*(future|task|promise).*", Pattern.CASE_INSENSITIVE);

    private static final String INTERRUPTED_EXCEPTION = "InterruptedException";
    private static final Set<String> BROAD_EXCEPTIONS = Set.of("Exception", "Throwable");
    // Методы, которыми бин останавливают: в них пул живет до конца работы приложения
    private static final Set<String> STOP_METHODS = Set.of("destroy", "close", "stop", "shutdown");

    @Override
    public String code() {
        return RuleCodes.EXECUTOR_GRACEFUL_SHUTDOWN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет остановку пула потоков без плавного завершения: shutdown() без"
                + " awaitTermination(), ожидание без shutdownNow() по таймауту и при InterruptedException";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        // Ожиданий в одном методе обычно два - до и после shutdownNow(): находка о них одна
        Set<String> reported = new HashSet<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            boolean tracked = SHUTDOWN.equals(name) || SHUTDOWN_NOW.equals(name) || AWAIT_TERMINATION.equals(name);
            if (!tracked || call.getScope().isEmpty() || !isExecutor(call.getScope().get())) {
                continue;
            }
            Optional<Node> callable = Nodes.enclosingCallable(call);
            if (callable.isEmpty() || isMain(callable.get())) {
                continue;
            }
            String executor = MethodCalls.receiverName(call.getScope().get());
            Optional<Violation> violation = switch (name) {
                case SHUTDOWN -> checkShutdown(sourceFile, call, executor, callable.get());
                case AWAIT_TERMINATION -> checkAwait(sourceFile, call, executor, callable.get());
                default -> checkShutdownNow(sourceFile, call, executor, callable.get());
            };
            violation.filter(found -> reported.add(key(callable.get(), executor, found)))
                    .ifPresent(violations::add);
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    // shutdown() только закрывает прием задач и сразу возвращает управление
    private Optional<Violation> checkShutdown(SourceFile sourceFile, MethodCallExpr call, String executor, Node callable) {
        if (!call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        boolean shared = isShared(call.getScope().get());
        Node region = regionOf(shared, callable);
        boolean waits = hasCall(region, executor, AWAIT_TERMINATION)
                || OTHER_WAITS.stream().anyMatch(method -> hasCall(region, executor, method))
                || isPassedOn(callable, executor)
                || waitsForTasks(callable);
        if (waits) {
            return Optional.empty();
        }
        if (shared) {
            return Optional.of(violation(sourceFile, call,
                    "Пул потоков '" + executor + "' останавливают одним shutdown(): он только закрывает прием новых"
                            + " задач и сразу возвращает управление, а задачи, которые еще выполняются, никто"
                            + " не ждет - приложение продолжит остановку (закроет соединения с базой, завершит"
                            + " JVM), и они оборвутся на середине; после shutdown() вызывайте awaitTermination(...),"
                            + " а по таймауту и при InterruptedException - shutdownNow()"));
        }
        // Локальный пул: задачи могли оставить работать в фоне намеренно
        return Optional.of(violation(sourceFile, call,
                "После shutdown() метод не ждет задачи пула '" + executor + "': shutdown() сразу возвращает"
                        + " управление, и метод закончится раньше, чем задачи, - вызывающий код увидит"
                        + " неполный результат; дождитесь их через awaitTermination(...) либо"
                        + " try-with-resources").withConfidence(Confidence.SUSPICION));
    }

    // Ожидание заканчивается тремя способами: задачи завершились, время вышло, поток прервали
    private Optional<Violation> checkAwait(SourceFile sourceFile, MethodCallExpr call, String executor, Node callable) {
        boolean shared = isShared(call.getScope().get());
        Node region = regionOf(shared, callable);
        boolean forces = hasCall(region, executor, SHUTDOWN_NOW) || isPassedOn(callable, executor);
        // while (!pool.awaitTermination(...)): ждут до конца, по времени пул останавливать не собираются
        boolean untilDone = isLoopCondition(call, callable);
        if (!forces && !untilDone) {
            return Optional.of(violation(sourceFile, call,
                    "После awaitTermination() пул потоков '" + executor + "' никто не останавливает принудительно:"
                            + " если задачи не уложились в отведенное время либо ожидание прервано, они"
                            + " продолжат работать, пока приложение останавливается; по таймауту и при"
                            + " InterruptedException вызывайте shutdownNow()"));
        }

        if (forcesInFinally(call, executor, callable)) {
            return Optional.empty();
        }
        Optional<CatchClause> handler = interruptHandler(call, callable);
        if (handler.isEmpty()) {
            return Optional.of(violation(sourceFile, call,
                    "InterruptedException из awaitTermination() уходит из метода, а пул потоков '" + executor
                            + "' остается работать: прерывание - просьба остановиться немедленно; перехватите"
                            + " его, вызовите shutdownNow() и восстановите флаг прерывания"
                            + " Thread.currentThread().interrupt()"));
        }
        if (calls(handler.get(), SHUTDOWN_NOW) || isPassedOn(handler.get(), executor)) {
            return Optional.empty();
        }
        // О потерянном флаге прерывания сообщает отдельное правило: здесь он только к слову
        boolean keepsInterrupt = calls(handler.get(), INTERRUPT) || !handler.get().findAll(ThrowStmt.class).isEmpty();
        return Optional.of(violation(sourceFile, handler.get(),
                "Ожидание пула потоков '" + executor + "' прервано, а пул не остановлен: прерывание - просьба"
                        + " остановиться немедленно, задачи же продолжат работать; в catch вызовите shutdownNow()"
                        + (keepsInterrupt ? "" : " и восстановите флаг прерывания Thread.currentThread().interrupt()")));
    }

    // shutdownNow() без попытки дождаться: выполняющиеся задачи прерываются, задачи из очереди отбрасываются
    private Optional<Violation> checkShutdownNow(SourceFile sourceFile, MethodCallExpr call, String executor, Node callable) {
        boolean shared = isShared(call.getScope().get());
        Node region = regionOf(shared, callable);
        if (!shared || !isStopMethod(callable) || hasCall(region, executor, SHUTDOWN)
                || hasCall(region, executor, AWAIT_TERMINATION)) {
            return Optional.empty();
        }
        return Optional.of(violation(sourceFile, call,
                "Пул потоков '" + executor + "' при остановке сразу прерывают через shutdownNow(): задачи, которые"
                        + " выполняются, получают прерывание, а задачи из очереди отбрасываются; если их работа"
                        + " важна, сначала вызовите shutdown() и awaitTermination(...), а shutdownNow() - только"
                        + " по таймауту").withLevel(ErrorLevel.MINOR).withConfidence(Confidence.SUSPICION));
    }

    private boolean isExecutor(Expression scope) {
        return LocalTypes.isAnyOf(scope, EXECUTOR_TYPES,
                () -> EXECUTOR_NAME.matcher(MethodCalls.receiverName(scope)).matches());
    }

    // Поле либо то, что возвращает метод: пул живет дольше одного вызова, и останавливать его могут в другом методе
    private boolean isShared(Expression scope) {
        Optional<Node> declaration = LocalTypes.findDeclaration(scope);
        if (declaration.isEmpty()) {
            return true;
        }
        return declaration.get() instanceof VariableDeclarator variable
                && variable.getParentNode().filter(parent -> parent instanceof FieldDeclaration).isPresent();
    }

    // Где искать остальные шаги остановки: у поля - во всем классе, у локальной переменной - в ее методе
    private Node regionOf(boolean shared, Node callable) {
        if (!shared) {
            return callable;
        }
        Node type = callable;
        for (Node current = callable; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof TypeDeclaration<?>) {
                type = current;
            }
        }
        return type;
    }

    private boolean hasCall(Node region, String executor, String method) {
        return region.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> method.equals(call.getNameAsString())
                        && call.getScope().filter(scope -> MethodCalls.receiverName(scope).equals(executor)).isPresent());
    }

    private boolean calls(Node region, String method) {
        return region.findAll(MethodCallExpr.class).stream().anyMatch(call -> method.equals(call.getNameAsString()));
    }

    // Пул отдали другому методу: MoreExecutors.shutdownAndAwaitTermination(pool, ...), awaitQuietly(pool)
    private boolean isPassedOn(Node region, String executor) {
        return region.findAll(MethodCallExpr.class).stream()
                .filter(call -> !Loggers.isLogCall(call))
                .flatMap(call -> call.getArguments().stream())
                .map(Nodes::unwrap)
                .filter(argument -> argument.isNameExpr() || argument.isFieldAccessExpr())
                .anyMatch(argument -> MethodCalls.receiverName(argument).equals(executor));
    }

    // Результаты уже собраны: pool.invokeAll(tasks), future.get(), latch.await(), CompletableFuture.allOf(...).join()
    private boolean waitsForTasks(Node callable) {
        return callable.findAll(MethodCallExpr.class).stream().anyMatch(call -> {
            if (TASK_WAITS.contains(call.getNameAsString())) {
                return true;
            }
            return GET.equals(call.getNameAsString()) && call.getScope()
                    .filter(scope -> LocalTypes.isAnyOf(scope, FUTURE_TYPES,
                            () -> FUTURE_NAME.matcher(MethodCalls.receiverName(scope)).matches()))
                    .isPresent();
        });
    }

    private boolean isLoopCondition(MethodCallExpr call, Node callable) {
        Node child = call;
        for (Node current = call.getParentNode().orElse(null); current != null && current != callable;
             current = current.getParentNode().orElse(null)) {
            if (current instanceof WhileStmt loop && loop.getCondition() == child
                    || current instanceof DoStmt repeat && repeat.getCondition() == child) {
                return true;
            }
            child = current;
        }
        return false;
    }

    // finally { pool.shutdownNow(); } срабатывает и по таймауту, и при прерывании
    private boolean forcesInFinally(MethodCallExpr call, String executor, Node callable) {
        for (Node current = call.getParentNode().orElse(null); current != null && current != callable;
             current = current.getParentNode().orElse(null)) {
            if (current instanceof TryStmt statement && statement.getFinallyBlock()
                    .filter(block -> hasCall(block, executor, SHUTDOWN_NOW) || isPassedOn(block, executor))
                    .isPresent()) {
                return true;
            }
        }
        return false;
    }

    // catch (InterruptedException e), в том числе в объединении, либо catch (Exception e) вокруг ожидания
    private Optional<CatchClause> interruptHandler(MethodCallExpr call, Node callable) {
        Node child = call;
        for (Node current = call.getParentNode().orElse(null); current != null && current != callable;
             current = current.getParentNode().orElse(null)) {
            if (current instanceof TryStmt statement && statement.getTryBlock() == child) {
                Optional<CatchClause> handler = statement.getCatchClauses().stream().filter(this::catchesInterrupt).findFirst();
                if (handler.isPresent()) {
                    return handler;
                }
            }
            child = current;
        }
        return Optional.empty();
    }

    private boolean catchesInterrupt(CatchClause clause) {
        Type type = clause.getParameter().getType();
        List<? extends Type> types = type.isUnionType() ? type.asUnionType().getElements() : List.of(type);
        return types.stream()
                .map(LocalTypes::typeName)
                .anyMatch(name -> INTERRUPTED_EXCEPTION.equals(name) || BROAD_EXCEPTIONS.contains(name));
    }

    // @PreDestroy, destroy(), close(), stop(): метод вызывают при остановке приложения
    private boolean isStopMethod(Node callable) {
        return callable instanceof MethodDeclaration method
                && (Annotations.has(method, PRE_DESTROY)
                || STOP_METHODS.contains(method.getNameAsString()) && method.getParameters().isEmpty());
    }

    // JVM сама дождется потоков пула после выхода из main: ожидание там не обязательно
    private boolean isMain(Node callable) {
        return callable instanceof MethodDeclaration method && method.isStatic() && MAIN.equals(method.getNameAsString())
                && method.getParameters().stream().map(Parameter::getType).anyMatch(type -> type.asString().startsWith("String"));
    }

    private String key(Node callable, String executor, Violation violation) {
        // Текст находки различает ее вид: о двух ожиданиях в одном методе находка одна, а о shutdown() - своя
        return System.identityHashCode(callable) + ":" + executor + ":" + violation.message();
    }
}
