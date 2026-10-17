package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class LockWithoutFinallyRule extends AbstractRule {
    private static final String UNLOCK = "unlock";
    private static final String LOCK_NAME = "lock";
    private static final String ACQUIRE = "acquire";
    private static final Set<String> LOCK_METHODS = Set.of("lock", "lockInterruptibly");
    private static final Set<String> LOCK_TYPES = Set.of(
            "Lock", "ReentrantLock", "ReadWriteLock", "ReentrantReadWriteLock", "ReadLock", "WriteLock", "StampedLock");

    @Override
    public String code() {
        return RuleCodes.LOCK_WITHOUT_FINALLY_RULE_CODE;
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
                .filter(call -> !isUnlockedInFinally(call) && !isDelegate(call) && !isReleasedElsewhere(call)
                        && !isUnlockedWithoutRisk(call))
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

    /**
     * Метод, который сам и есть захват замка: {@code public void lock() { lock.lock(); }} в обертке над замком.
     * Освобождает его вызывающий код.
     */
    private boolean isDelegate(MethodCallExpr lock) {
        return lock.findAncestor(MethodDeclaration.class)
                .map(method -> method.getNameAsString().toLowerCase(Locale.ROOT))
                .filter(name -> name.startsWith(LOCK_NAME) || name.startsWith(ACQUIRE))
                .isPresent();
    }

    // Замок захватывают в одном методе, а освобождают в другом: begin() / end(), open() / close()
    private boolean isReleasedElsewhere(MethodCallExpr lock) {
        String name = lock.getScope().map(Expression::toString).orElse("");
        Optional<Node> callable = Nodes.enclosingCallable(lock);
        Optional<ClassOrInterfaceDeclaration> owner = lock.findAncestor(ClassOrInterfaceDeclaration.class);
        if (callable.isEmpty() || owner.isEmpty() || !unlocksOf(callable.get(), name).isEmpty()) {
            return false;
        }
        return !unlocksOf(owner.get(), name).isEmpty();
    }

    /**
     * Между захватом и освобождением нет ничего, что может бросить исключение: только чтение полей и
     * арифметика. {@code for (...) segments[i].lock(); for (...) sum += segments[i].count; for (...) segments[i].unlock();}
     */
    private boolean isUnlockedWithoutRisk(MethodCallExpr lock) {
        String name = lock.getScope().map(Expression::toString).orElse("");
        Optional<Node> callable = Nodes.enclosingCallable(lock);
        if (callable.isEmpty()) {
            return false;
        }
        for (MethodCallExpr unlock : unlocksOf(callable.get(), name)) {
            Optional<List<Statement>> between = between(lock, unlock);
            if (between.isPresent() && between.get().stream().allMatch(statement -> isSafe(statement, lock, unlock))) {
                return true;
            }
        }
        return false;
    }

    // Операторы одного блока от того, где захват, до того, где освобождение, включительно
    private Optional<List<Statement>> between(MethodCallExpr lock, MethodCallExpr unlock) {
        Optional<Statement> start = lock.findAncestor(Statement.class);
        while (start.isPresent()) {
            Statement statement = start.get();
            Optional<Node> parent = statement.getParentNode();
            if (parent.isPresent() && parent.get() instanceof BlockStmt block && block.isAncestorOf(unlock)) {
                List<Statement> statements = block.getStatements();
                int from = 0;
                // indexOf сравнивает узлы по тексту, а одинаковых операторов в блоке может быть несколько
                while (statements.get(from) != statement) {
                    from++;
                }
                for (int index = from; index < statements.size(); index++) {
                    if (statements.get(index).isAncestorOf(unlock)) {
                        return Optional.of(statements.subList(from, index + 1));
                    }
                }
                return Optional.empty();
            }
            start = statement.findAncestor(Statement.class);
        }
        return Optional.empty();
    }

    private boolean isSafe(Statement statement, MethodCallExpr lock, MethodCallExpr unlock) {
        return statement.findAll(MethodCallExpr.class).stream().allMatch(call -> call == lock || call == unlock)
                && statement.findAll(ObjectCreationExpr.class).isEmpty()
                && statement.findAll(ThrowStmt.class).isEmpty()
                && statement.findAll(ReturnStmt.class).isEmpty();
    }

    private List<MethodCallExpr> unlocksOf(Node scope, String name) {
        return scope.findAll(MethodCallExpr.class).stream()
                .filter(call -> UNLOCK.equals(call.getNameAsString()))
                .filter(call -> call.getScope().filter(receiver -> receiver.toString().equals(name)).isPresent())
                .toList();
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
