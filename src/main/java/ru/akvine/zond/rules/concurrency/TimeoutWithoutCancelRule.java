package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code try { return future.get(5, SECONDS); } catch (TimeoutException e) { throw new ServiceException(e); }}:
 * ждать перестали, а задача продолжает выполняться и занимает поток пула, хотя ее результат уже никому не нужен.
 * Если задачи зависают или тянутся долго, с каждым истекшим сроком в пуле становится на поток меньше, и в конце
 * концов он перестает принимать работу. Срок без отмены ограничивает только ожидание, а не саму работу.
 * <p>
 * Отменяет задачу тот, кто ее запустил. Если она пришла в метод параметром, отмену ищем у вызывающих: в их catch
 * и finally вокруг вызова.
 */
@Component
public class TimeoutWithoutCancelRule extends AbstractRule implements ProjectRule {
    private static final String GET = "get";
    private static final int TIMED_GET_ARGUMENTS = 2;
    private static final String TIMEOUT_EXCEPTION = "TimeoutException";
    private static final String TIME_UNIT = "TimeUnit";
    private static final Set<String> TIME_UNITS =
            Set.of("NANOSECONDS", "MICROSECONDS", "MILLISECONDS", "SECONDS", "MINUTES", "HOURS", "DAYS");
    // Отмена задачи, остановка пула, прерывание потока, завершение CompletableFuture ошибкой, остановка процесса
    private static final Set<String> CANCELLATIONS =
            Set.of("cancel", "cancelAll", "shutdownNow", "interrupt", "completeExceptionally", "obtrudeException",
                    "destroy", "destroyForcibly", "abort");
    private static final Set<String> BROAD_EXCEPTIONS = Set.of("Exception", "Throwable");
    private static final String THIS_PREFIX = "this.";
    // await(future) <- collect(...) <- generate(...) <- generate(request): отмена бывает на несколько вызовов выше
    private static final int MAX_CALLER_DEPTH = 4;

    @Override
    public String code() {
        return RuleCodes.TIMEOUT_WITHOUT_CANCEL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ожидание задачи со сроком, после истечения которого задачу не отменяют:"
                + " future.get(timeout, unit) и catch (TimeoutException) без cancel";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            if (TestPaths.isTest(sourceFile.path())) {
                continue;
            }
            for (TryStmt attempt : sourceFile.findAll(TryStmt.class)) {
                check(sourceFile, attempt, sourceFiles).ifPresent(violations::add);
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

    private Optional<Violation> check(SourceFile sourceFile, TryStmt attempt, List<SourceFile> sourceFiles) {
        Optional<CatchClause> timeout = findTimeoutCatch(attempt);
        if (timeout.isEmpty()) {
            return Optional.empty();
        }
        Optional<MethodCallExpr> wait = attempt.getTryBlock().findAll(MethodCallExpr.class).stream()
                .filter(call -> isTimedGet(call) && !Nodes.isInNestedScope(call, attempt) && !isInnerTry(call, attempt))
                .findFirst();
        if (wait.isEmpty()) {
            return Optional.empty();
        }

        Expression task = Nodes.unwrap(wait.get().getScope().get());
        String name = task.toString();
        boolean handled = isCancelled(timeout.get().getBody(), name)
                || attempt.getFinallyBlock().filter(block -> isCancelled(block, name)).isPresent();
        if (handled) {
            return Optional.empty();
        }
        return confidenceOf(task, sourceFiles).map(confidence -> violation(sourceFile, wait.get(),
                "Срок ожидания '" + wait.get() + "' истекает, а задачу никто не отменяет: она продолжает"
                        + " выполняться и занимать поток пула, хотя результат уже не нужен, и с каждым"
                        + " истекшим сроком свободных потоков все меньше; при TimeoutException"
                        + " вызовите " + name + ".cancel(true), а в самой задаче проверяйте"
                        + " прерывание")
                .withConfidence(confidence));
    }

    // Пусто, если задачу отменяет тот, кто ее передал: находки нет
    private Optional<Confidence> confidenceOf(Expression task, List<SourceFile> sourceFiles) {
        Optional<MethodDeclaration> receiver = receiverOf(task);
        if (receiver.isEmpty()) {
            // Задача лежит в поле, и класс отменяет ее в другом методе: до тех пор она все же выполняется
            return Optional.of(isCancelledElsewhereInClass(task) ? Confidence.SUSPICION : Confidence.PROBABLE);
        }
        // Граф вызовов строится один раз на проверку и только если такая задача нашлась
        CallGraph graph = CallGraph.of(sourceFiles);
        if (isCancelledByCaller(receiver.get(), graph, MAX_CALLER_DEPTH, newIdentitySet())) {
            return Optional.empty();
        }
        // Вызовов в проекте нет: метод вызывают снаружи, и отменяют ли задачу там, неизвестно
        return Optional.of(graph.callsTo(receiver.get()).isEmpty() ? Confidence.SUSPICION : Confidence.PROBABLE);
    }

    // catch (TimeoutException e), catch (InterruptedException | TimeoutException e); если такого нет, истечение
    // срока попадает в catch (Exception e)
    private Optional<CatchClause> findTimeoutCatch(TryStmt attempt) {
        Optional<CatchClause> exact = attempt.getCatchClauses().stream()
                .filter(this::catchesTimeout)
                .findFirst();
        return exact.isPresent() ? exact : attempt.getCatchClauses().stream()
                .filter(clause -> BROAD_EXCEPTIONS.contains(clause.getParameter().getType().toString()))
                .findFirst();
    }

    private boolean catchesTimeout(CatchClause clause) {
        return clause.getParameter().getType().toString().contains(TIMEOUT_EXCEPTION);
    }

    // Отменить можно только задачу, которая лежит в переменной: client.send(...).get(1, SECONDS) отменять не у чего
    private boolean isTimedGet(MethodCallExpr call) {
        if (!GET.equals(call.getNameAsString()) || call.getArguments().size() != TIMED_GET_ARGUMENTS
                || call.getScope().isEmpty() || !isTimeUnit(call.getArgument(1))) {
            return false;
        }
        Expression scope = Nodes.unwrap(call.getScope().get());
        return scope.isNameExpr() || scope.isFieldAccessExpr();
    }

    // Два аргумента бывают и у cache.get(key, loader), и у Paths.get(first, second): срок выдает единица времени
    private boolean isTimeUnit(Expression argument) {
        Expression unit = Nodes.unwrap(argument);
        if (unit.isFieldAccessExpr()) {
            return TIME_UNITS.contains(unit.asFieldAccessExpr().getNameAsString());
        }
        return unit.isNameExpr() && (TIME_UNITS.contains(unit.asNameExpr().getNameAsString())
                || LocalTypes.typeOf(unit).filter(TIME_UNIT::equals).isPresent());
    }

    // Отмена вызвана прямо здесь либо задачу передали методу, который ею займется: handleTimeout(future)
    private boolean isCancelled(Node block, String task) {
        return block.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> CANCELLATIONS.contains(call.getNameAsString())
                        || call.getArguments().stream()
                        .anyMatch(argument -> Nodes.unwrap(argument).toString().equals(task)));
    }

    // Ожидание внутри вложенного try со своим catch (TimeoutException) относится к нему
    private boolean isInnerTry(MethodCallExpr call, TryStmt attempt) {
        Node current = call.getParentNode().orElse(null);
        while (current != null && current != attempt) {
            if (current instanceof TryStmt inner && findTimeoutCatch(inner).isPresent()) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * @return метод, в который задача пришла параметром: запустил ее не он, и отменять ее - дело вызывающего
     */
    private Optional<MethodDeclaration> receiverOf(Expression task) {
        if (!task.isNameExpr()) {
            return Optional.empty();
        }
        return LocalTypes.findDeclaration(task)
                .filter(Parameter.class::isInstance)
                .flatMap(Node::getParentNode)
                .filter(MethodDeclaration.class::isInstance)
                .map(MethodDeclaration.class::cast);
    }

    // try { collect(futures); } catch (RuntimeException e) { generation.cancel(); throw e; }
    private boolean isCancelledByCaller(MethodDeclaration method, CallGraph graph, int depth,
                                        Set<MethodDeclaration> visited) {
        if (depth == 0 || !visited.add(method)) {
            return false;
        }
        for (CallGraph.Call call : graph.callsTo(method)) {
            if (cancelsOnFailure(call.site()) || isCancelledByCaller(call.caller(), graph, depth - 1, visited)) {
                return true;
            }
        }
        return false;
    }

    // Вызов стоит в try, у которого отмена есть в catch или finally
    private boolean cancelsOnFailure(MethodCallExpr site) {
        Node child = site;
        Node current = site.getParentNode().orElse(null);
        while (current != null && !(current instanceof MethodDeclaration)) {
            if (current instanceof TryStmt attempt && attempt.getTryBlock() == child && cancels(attempt)) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean cancels(TryStmt attempt) {
        List<Node> handlers = new ArrayList<>(attempt.getCatchClauses());
        attempt.getFinallyBlock().ifPresent(handlers::add);
        return handlers.stream()
                .flatMap(handler -> handler.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> CANCELLATIONS.contains(call.getNameAsString()));
    }

    // this.task.get(5, SECONDS) в одном методе и task.cancel(true) в close()
    private boolean isCancelledElsewhereInClass(Expression task) {
        String name = task.toString().startsWith(THIS_PREFIX)
                ? task.toString().substring(THIS_PREFIX.length())
                : task.toString();
        boolean field = task.isFieldAccessExpr() || LocalTypes.findDeclaration(task)
                .flatMap(Node::getParentNode)
                .filter(FieldDeclaration.class::isInstance)
                .isPresent();
        if (!field) {
            return false;
        }
        Node current = task.getParentNode().orElse(null);
        while (current != null && !(current instanceof TypeDeclaration<?>)) {
            current = current.getParentNode().orElse(null);
        }
        return current != null && current.findAll(MethodCallExpr.class).stream()
                .filter(call -> CANCELLATIONS.contains(call.getNameAsString()))
                .flatMap(call -> call.getScope().stream())
                .map(scope -> Nodes.unwrap(scope).toString())
                .anyMatch(scope -> scope.equals(name) || scope.equals(THIS_PREFIX + name));
    }

    private Set<MethodDeclaration> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
