package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallChains;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Медленная внешняя операция внутри транзакции: пока она идет, транзакция держит соединение с БД.
 * Операция ищется и в самом транзакционном методе, и в методах, которые он вызывает.
 */
public abstract class AbstractTransactionalBlockingCallRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь от транзакционного метода искать операцию; 0 - не искать");
    private static final String ASYNC = "Async";

    /**
     * @return описание операции (например, restTemplate.postForObject), если узел - блокирующий вызов
     */
    protected abstract Optional<String> describeBlockingCall(Node node);

    /**
     * @return текст нарушения для операции, найденной в транзакционном методе
     */
    protected abstract String message(String method, String call);

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (isTransactional(method)) {
                    check(sourceFile, method, graph, violations);
                }
            }
        }
        return violations;
    }

    private void check(SourceFile sourceFile, MethodDeclaration method, CallGraph graph, List<Violation> violations) {
        // webClient.get().uri(...).retrieve() - одна операция, а не три
        Set<Integer> reportedLines = new HashSet<>();
        for (Node node : method.getBody().get().findAll(Node.class)) {
            Optional<String> call = describeBlockingCall(node);
            if (call.isPresent() && reportedLines.add(line(node))) {
                violations.add(violation(sourceFile, node, message(method.getNameAsString(), call.get())));
            }
        }

        // Операция спрятана в вызванном методе: транзакция при этом та же. Сообщаем о вызове, который к ней ведет
        for (CallGraph.Call call : graph.callsFrom(method)) {
            if (call.target() == method) {
                continue;
            }
            CallChains.find(graph, call.target(), value(MAX_CALL_DEPTH), this::runsOutsideTransaction,
                            this::describeBlockingCall)
                    .filter(found -> reportedLines.add(line(call.site())))
                    .ifPresent(found -> violations.add(violation(sourceFile, call.site(), message(
                            method.getNameAsString(),
                            found.operation() + " (через вызов " + found.chain() + ")"))));
        }
    }

    // На приватных методах @Transactional не работает в принципе, это ловит отдельное правило
    private boolean isTransactional(MethodDeclaration method) {
        return !method.isPrivate()
                && method.getBody().isPresent()
                && TransactionalAnnotations.findEffective(method).isPresent();
    }

    // @Async-метод выполняется в другом потоке, вне транзакции вызывающего; о транзакционном методе
    // правило сообщит отдельно, когда дойдет до него самого
    private boolean runsOutsideTransaction(MethodDeclaration method) {
        return Annotations.has(method, ASYNC) || isTransactional(method);
    }

    private int line(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }
}
