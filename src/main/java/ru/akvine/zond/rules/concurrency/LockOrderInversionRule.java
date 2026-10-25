package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * transfer() берет synchronized (from), а внутри synchronized (to), а refund() - в обратном порядке: два потока,
 * которые вызвали их одновременно, держат по замку и ждут друг друга вечно. Проверяется порядок вложенных
 * synchronized и lock() у замков ReentrantLock в пределах одного класса
 */
@Component
public class LockOrderInversionRule extends AbstractRule {
    private static final String THIS = "this";
    private static final String THIS_PREFIX = "this.";
    private static final String LOCK = "lock";
    private static final String LOCK_INTERRUPTIBLY = "lockInterruptibly";
    private static final String UNLOCK = "unlock";

    /**
     * Замок inner берется, пока держится outer
     */
    private record Nesting(String outer, String inner, String method, Node place) {
    }

    @Override
    public String code() {
        return RuleCodes.LOCK_ORDER_INVERSION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет два замка, которые разные методы класса захватывают в обратном порядке:"
                + " так возникает взаимная блокировка";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            List<Nesting> nestings = new ArrayList<>();
            for (MethodDeclaration method : type.getMethods()) {
                collectSynchronized(method, nestings);
                collectExplicitLocks(method, nestings);
            }
            Set<String> reported = new HashSet<>();
            for (Nesting nesting : nestings) {
                for (Nesting other : nestings) {
                    boolean inverted = nesting.outer().equals(other.inner()) && nesting.inner().equals(other.outer());
                    String key = String.join("|", new TreeSet<>(List.of(nesting.outer(), nesting.inner())));
                    if (inverted && line(nesting.place()) > line(other.place()) && reported.add(key)) {
                        violations.add(violation(sourceFile, nesting.place(),
                                "Замки '" + nesting.outer() + "' и '" + nesting.inner() + "' берутся в разном"
                                        + " порядке: в '" + other.method() + "' сначала '" + other.outer() + "', а в '"
                                        + nesting.method() + "' сначала '" + nesting.outer() + "'; два потока,"
                                        + " вызвавшие их одновременно, будут вечно ждать друг друга; берите замки"
                                        + " везде в одном порядке"));
                    }
                }
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

    // synchronized (a) { synchronized (b) { ... } }; synchronized-метод держит this
    private void collectSynchronized(MethodDeclaration method, List<Nesting> nestings) {
        for (SynchronizedStmt block : method.findAll(SynchronizedStmt.class)) {
            if (Nodes.isInNestedScope(block, method)) {
                continue;
            }
            String inner = lockName(block.getExpression());
            Node current = block.getParentNode().orElse(null);
            while (current != null && current != method) {
                if (current instanceof SynchronizedStmt outer) {
                    add(nestings, lockName(outer.getExpression()), inner, method, block);
                }
                current = current.getParentNode().orElse(null);
            }
            if (method.isSynchronized()) {
                add(nestings, THIS, inner, method, block);
            }
        }
    }

    // first.lock(); try { second.lock(); ... } - замки, которые держатся в момент очередного lock()
    private void collectExplicitLocks(MethodDeclaration method, List<Nesting> nestings) {
        List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class).stream()
                .filter(call -> !Nodes.isInNestedScope(call, method) && call.getArguments().isEmpty())
                .filter(call -> call.getScope().isPresent())
                .sorted(Comparator.comparingInt(this::line))
                .toList();
        Map<String, MethodCallExpr> held = new LinkedHashMap<>();
        for (MethodCallExpr call : calls) {
            String name = call.getNameAsString();
            String lock = lockName(call.getScope().get());
            if (LOCK.equals(name) || LOCK_INTERRUPTIBLY.equals(name)) {
                held.keySet().forEach(outer -> add(nestings, outer, lock, method, call));
                held.put(lock, call);
            } else if (UNLOCK.equals(name)) {
                held.remove(lock);
            }
        }
    }

    private void add(List<Nesting> nestings, String outer, String inner, MethodDeclaration method, Node place) {
        if (!outer.equals(inner)) {
            nestings.add(new Nesting(outer, inner, method.getNameAsString(), place));
        }
    }

    private String lockName(Expression expression) {
        String text = Nodes.unwrap(expression).toString();
        return text.startsWith(THIS_PREFIX) ? text.substring(THIS_PREFIX.length()) : text;
    }

    private int line(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }
}
