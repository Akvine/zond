package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * wait(), notify() и notifyAll() вне synchronized по тому же объекту. Вызывать их может только поток, который
 * владеет монитором объекта; иначе вызов заканчивается IllegalMonitorStateException.
 */
@Component
public class WaitNotifyOutsideSynchronizedRule extends AbstractRule {
    private static final String WAIT = "wait";
    private static final Set<String> NOTIFY_METHODS = Set.of("notify", "notifyAll");
    // wait(), wait(timeout), wait(timeout, nanos)
    private static final int WAIT_MAX_ARGUMENTS = 2;
    private static final String THIS = "this";
    private static final String THIS_PREFIX = "this.";
    private static final String CLASS_SUFFIX = ".class";

    /**
     * Чем закончился поиск монитора вокруг вызова
     *
     * @param held  монитор нужного объекта захвачен либо судить об этом нельзя
     * @param other объект, по которому синхронизация есть, хотя нужен другой
     */
    private record Monitor(boolean held, Optional<String> other) {
    }

    @Override
    public String code() {
        return RuleCodes.WAIT_NOTIFY_OUTSIDE_SYNCHRONIZED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет wait(), notify() и notifyAll() вне synchronized по тому же объекту";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Свой метод wait(long) с другим смыслом: тогда вызовы с аргументами к Object.wait не относятся
        boolean ownWait = sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .anyMatch(method -> WAIT.equals(method.getNameAsString()));
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!isMonitorCall(call, ownWait)) {
                continue;
            }
            String target = call.getScope().map(scope -> normalize(Nodes.unwrap(scope))).orElse(THIS);
            Monitor monitor = monitorAround(call, target);
            if (monitor.held()) {
                continue;
            }
            String described = "'" + call.getScope().map(scope -> scope + ".").orElse("") + call.getNameAsString() + "()'";
            violations.add(violation(sourceFile, call, monitor.other()
                    .map(other -> described + " внутри synchronized по другому объекту ('" + other + "'): монитор '"
                            + target + "' при этом не захвачен, и вызов закончится IllegalMonitorStateException;"
                            + " синхронизируйтесь по тому же объекту, на котором вызываете метод")
                    .orElseGet(() -> described + " вне synchronized: поток не владеет монитором объекта '" + target
                            + "', и вызов закончится IllegalMonitorStateException; вызывайте wait(), notify()"
                            + " и notifyAll() внутри synchronized (" + target + ") { ... } либо в synchronized-методе")));
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

    // notify() и notifyAll() без аргументов и wait() - методы Object: они final, переопределить их нельзя
    private boolean isMonitorCall(MethodCallExpr call, boolean ownWait) {
        String name = call.getNameAsString();
        if (NOTIFY_METHODS.contains(name)) {
            return call.getArguments().isEmpty();
        }
        return WAIT.equals(name)
                && (call.getArguments().isEmpty() || !ownWait && call.getArguments().size() <= WAIT_MAX_ARGUMENTS);
    }

    // this.lock и lock - один и тот же объект
    private String normalize(Expression expression) {
        String text = expression.toString();
        return text.startsWith(THIS_PREFIX) ? text.substring(THIS_PREFIX.length()) : text;
    }

    private Monitor monitorAround(MethodCallExpr call, String target) {
        Optional<String> other = Optional.empty();
        for (Node current = call.getParentNode().orElse(null); current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof SynchronizedStmt block) {
                String lock = normalize(Nodes.unwrap(block.getExpression()));
                if (lock.equals(target)) {
                    return new Monitor(true, Optional.empty());
                }
                other = other.or(() -> Optional.of(lock));
            }
            // Лямбду выполнят неизвестно когда и где: держит ли тот поток монитор, по коду не видно
            if (current instanceof LambdaExpr) {
                return new Monitor(true, Optional.empty());
            }
            if (current instanceof MethodDeclaration method) {
                return inMethod(method, target, other);
            }
            if (current instanceof ConstructorDeclaration || current instanceof InitializerDeclaration) {
                break;
            }
        }
        return new Monitor(false, other);
    }

    // synchronized-метод держит монитор this, статический - монитор класса
    private Monitor inMethod(MethodDeclaration method, String target, Optional<String> other) {
        if (method.isSynchronized()) {
            boolean own = method.isStatic() ? target.endsWith(CLASS_SUFFIX) : THIS.equals(target);
            return new Monitor(own, own ? Optional.empty() : other.or(() -> Optional.of(method.isStatic() ? "класс" : THIS)));
        }
        // Закрытый помощник, который вызывают только под блокировкой: монитор держит вызывающий
        return new Monitor(other.isEmpty() && isCalledOnlyUnderLock(method), other);
    }

    private boolean isCalledOnlyUnderLock(MethodDeclaration method) {
        Optional<TypeDeclaration<?>> owner = method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?>)
                .map(parent -> (TypeDeclaration<?>) parent);
        if (owner.isEmpty()) {
            return false;
        }
        List<MethodCallExpr> callers = owner.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals(method.getNameAsString())
                        && call.getArguments().size() == method.getParameters().size()
                        && call.getScope().map(Expression::isThisExpr).orElse(true)
                        && call.findAncestor(MethodDeclaration.class).filter(caller -> caller == method).isEmpty())
                .toList();
        return !callers.isEmpty() && callers.stream().allMatch(this::isUnderLock);
    }

    private boolean isUnderLock(MethodCallExpr call) {
        return call.findAncestor(SynchronizedStmt.class).isPresent()
                || call.findAncestor(MethodDeclaration.class).filter(MethodDeclaration::isSynchronized).isPresent();
    }
}
