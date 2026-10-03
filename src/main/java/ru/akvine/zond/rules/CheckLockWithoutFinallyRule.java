package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckLockWithoutFinallyRule extends AbstractRule {
    private static final String UNLOCK = "unlock";
    private static final String LOCK_NAME = "lock";
    private static final Set<String> LOCK_METHODS = Set.of("lock", "lockInterruptibly");
    private static final Set<String> LOCK_TYPES = Set.of(
            "Lock", "ReentrantLock", "ReadWriteLock", "ReentrantReadWriteLock", "ReadLock", "WriteLock", "StampedLock");

    @Override
    public String code() {
        return RuleCodes.CHECK_LOCK_WITHOUT_FINALLY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Lock.lock() без unlock() в блоке finally";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> LOCK_METHODS.contains(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(call -> call.getScope().filter(this::isLock).isPresent())
                .filter(call -> !isUnlockedInFinally(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' без unlock() в finally: при исключении замок останется захваченным навсегда,"
                                + " и все потоки, которым он нужен, зависнут; освобождайте замок в finally"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    // По типу; если тип определить не удалось - по имени: lock, readLock(), writeLock.
    // Именно окончание имени: иначе под правило попадет blockService.lock()
    private boolean isLock(Expression scope) {
        return LocalTypes.isAnyOf(scope, LOCK_TYPES,
                () -> MethodCalls.receiverName(scope).toLowerCase().endsWith(LOCK_NAME));
    }

    // В том же методе есть unlock() того же замка, и стоит он в finally
    private boolean isUnlockedInFinally(MethodCallExpr lock) {
        String name = lock.getScope().map(Expression::toString).orElse("");
        Optional<Node> callable = Nodes.enclosingCallable(lock);
        return callable.isPresent() && callable.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> UNLOCK.equals(call.getNameAsString()))
                .filter(call -> call.getScope().filter(scope -> scope.toString().equals(name)).isPresent())
                .anyMatch(this::isInFinally);
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
}
