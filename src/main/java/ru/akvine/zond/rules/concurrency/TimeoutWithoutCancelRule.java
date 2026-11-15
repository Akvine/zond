package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code try { return future.get(5, SECONDS); } catch (TimeoutException e) { throw new ServiceException(e); }}:
 * ждать перестали, а задача продолжает выполняться и занимает поток пула, хотя ее результат уже никому не нужен.
 * Если задачи зависают или тянутся долго, с каждым истекшим сроком в пуле становится на поток меньше, и в конце
 * концов он перестает принимать работу. Срок без отмены ограничивает только ожидание, а не саму работу.
 */
@Component
public class TimeoutWithoutCancelRule extends AbstractRule {
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
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        if (TestPaths.isTest(sourceFile.path())) {
            return violations;
        }

        for (TryStmt attempt : sourceFile.unit().findAll(TryStmt.class)) {
            Optional<CatchClause> timeout = findTimeoutCatch(attempt);
            if (timeout.isEmpty()) {
                continue;
            }
            for (MethodCallExpr call : attempt.getTryBlock().findAll(MethodCallExpr.class)) {
                if (!isTimedGet(call) || Nodes.isInNestedScope(call, attempt) || isInnerTry(call, attempt)) {
                    continue;
                }
                String future = Nodes.unwrap(call.getScope().get()).toString();
                boolean handled = isCancelled(timeout.get().getBody(), future)
                        || attempt.getFinallyBlock().filter(block -> isCancelled(block, future)).isPresent();
                if (!handled) {
                    violations.add(violation(sourceFile, call,
                            "Срок ожидания '" + call + "' истекает, а задачу никто не отменяет: она продолжает"
                                    + " выполняться и занимать поток пула, хотя результат уже не нужен, и с каждым"
                                    + " истекшим сроком свободных потоков все меньше; при TimeoutException"
                                    + " вызовите " + future + ".cancel(true), а в самой задаче проверяйте"
                                    + " прерывание"));
                }
                break;
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
    private boolean isCancelled(Node block, String future) {
        return block.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> CANCELLATIONS.contains(call.getNameAsString())
                        || call.getArguments().stream()
                        .anyMatch(argument -> Nodes.unwrap(argument).toString().equals(future)));
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
}
