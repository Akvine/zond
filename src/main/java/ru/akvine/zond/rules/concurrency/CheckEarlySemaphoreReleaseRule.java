package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
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
public class CheckEarlySemaphoreReleaseRule extends AbstractRule {
    private static final String SEMAPHORE = "Semaphore";
    private static final String RELEASE = "release";
    private static final String START = "start";
    private static final String EXECUTE = "execute";
    private static final String THREAD = "Thread";
    private static final Set<String> ACQUIRE_METHODS = Set.of("acquire", "acquireUninterruptibly", "tryAcquire");

    // Запускают работу в другом потоке и сразу возвращают управление
    private static final Set<String> ASYNC_METHODS = Set.of("submit", "schedule", "runAsync", "supplyAsync");

    @Override
    public String code() {
        return RuleCodes.CHECK_EARLY_SEMAPHORE_RELEASE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Semaphore.release() до завершения работы с ресурсом";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr release : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!RELEASE.equals(release.getNameAsString())
                    || release.getScope().filter(this::isSemaphore).isEmpty()) {
                continue;
            }

            Optional<Node> callable = Nodes.enclosingCallable(release);
            if (callable.isEmpty()) {
                continue;
            }

            // Без захвата в этом же методе непонятно, где начинается работа с ресурсом
            Optional<MethodCallExpr> acquire = findAcquire(callable.get(), release);
            if (acquire.isEmpty()) {
                continue;
            }

            String semaphore = release.getScope().get().toString();
            Optional<MethodCallExpr> asyncWork = findAsyncWork(callable.get(), acquire.get(), release);
            if (asyncWork.isPresent()) {
                violations.add(violation(sourceFile, release,
                        "'" + semaphore + ".release()' вызывается сразу после запуска асинхронной задачи "
                                + asyncWork.get().getNameAsString() + "(...): разрешение освобождается, пока задача"
                                + " еще работает с ресурсом; освобождайте его в finally внутри самой задачи"));
            } else if (!isInFinally(release) && hasWorkAfter(release)) {
                violations.add(violation(sourceFile, release,
                        "'" + semaphore + ".release()' вызывается до завершения работы: код после него выполняется"
                                + " уже без разрешения; освобождайте семафор в finally после работы с ресурсом"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    // По типу; если тип определить не удалось - по имени
    private boolean isSemaphore(Expression scope) {
        return LocalTypes.isAnyOf(scope, Set.of(SEMAPHORE),
                () -> scope.toString().toLowerCase().contains(SEMAPHORE.toLowerCase()));
    }

    // Захват того же семафора выше по методу
    private Optional<MethodCallExpr> findAcquire(Node callable, MethodCallExpr release) {
        String semaphore = release.getScope().get().toString();
        return callable.findAll(MethodCallExpr.class).stream()
                .filter(call -> ACQUIRE_METHODS.contains(call.getNameAsString()))
                .filter(call -> call.getScope().filter(scope -> scope.toString().equals(semaphore)).isPresent())
                .filter(call -> isBefore(call, release))
                .findFirst();
    }

    // Асинхронный запуск между захватом и освобождением. Если release() стоит внутри самой задачи - все верно
    private Optional<MethodCallExpr> findAsyncWork(Node callable, MethodCallExpr acquire, MethodCallExpr release) {
        return callable.findAll(MethodCallExpr.class).stream()
                .filter(this::isAsyncStart)
                .filter(call -> isBefore(acquire, call) && isBefore(call, release))
                .filter(call -> !call.isAncestorOf(release))
                .findFirst();
    }

    private boolean isAsyncStart(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (ASYNC_METHODS.contains(name)) {
            return !call.getArguments().isEmpty();
        }
        // execute есть и у JdbcTemplate, и у Statement, поэтому берем только execute с лямбдой или ссылкой на метод
        if (EXECUTE.equals(name)) {
            return call.getArguments().stream()
                    .anyMatch(argument -> argument.isLambdaExpr() || argument.isMethodReferenceExpr());
        }
        return START.equals(name)
                && call.getScope().flatMap(LocalTypes::typeOf).filter(THREAD::equals).isPresent();
    }

    private boolean isInFinally(Node node) {
        Node child = node;
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            Node block = child;
            if (current instanceof TryStmt tryStmt && tryStmt.getFinallyBlock().filter(body -> body == block).isPresent()) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // После release() в том же блоке есть что-то кроме выхода из метода или цикла
    private boolean hasWorkAfter(MethodCallExpr release) {
        Node statement = release;
        while (statement != null && !(statement instanceof Statement)) {
            statement = statement.getParentNode().orElse(null);
        }
        if (statement == null || !(statement.getParentNode().orElse(null) instanceof BlockStmt block)) {
            return false;
        }

        List<Statement> statements = block.getStatements();
        return statements.subList(statements.indexOf(statement) + 1, statements.size()).stream()
                .anyMatch(next -> !next.isReturnStmt() && !next.isBreakStmt()
                        && !next.isContinueStmt() && !next.isThrowStmt());
    }

    private boolean isBefore(Node first, Node second) {
        Optional<Position> firstPosition = first.getBegin();
        Optional<Position> secondPosition = second.getBegin();
        return firstPosition.isPresent() && secondPosition.isPresent()
                && firstPosition.get().isBefore(secondPosition.get());
    }
}
