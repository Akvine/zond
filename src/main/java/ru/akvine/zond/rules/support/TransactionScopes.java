package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import lombok.experimental.UtilityClass;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Выполняется ли код в транзакции: по аннотации метода, по обратному вызову {@code TransactionTemplate},
 * по ручному управлению транзакцией либо по цепочке вызовов, которая вся идет через транзакцию
 */
@UtilityClass
public class TransactionScopes {
    private static final int CALLER_DEPTH = 4;

    private static final Set<String> TEMPLATE_TYPES = Set.of(
            "TransactionTemplate", "TransactionOperations", "TransactionalOperator");
    private static final Pattern TEMPLATE_NAME =
            Pattern.compile(".*(transaction|tx)(template|operations|operator)$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TEMPLATE_METHODS = Set.of("execute", "executeWithoutResult");

    // jdbi.inTransaction(...), dsl.transaction(...), helper.runInTransaction(...), doInTx(...)
    private static final Pattern CALLBACK_METHOD = Pattern.compile(
            "transaction|transactionResult|transactional|\\w*(In|With|Use|Within)(New)?(Transaction|Tx)\\w*"
                    + "|(in|use|with)(New)?(Transaction|Tx)\\w*");

    // Контейнер EJB открывает транзакцию на каждый метод такого бина
    private static final Set<String> CONTAINER_MANAGED = Set.of("Stateless", "Stateful", "MessageDriven");

    private static final String SET_AUTO_COMMIT = "setAutoCommit";
    private static final Set<String> MANUAL_START = Set.of(
            "getTransaction", "beginTransaction", "createTransaction", "commit");
    private static final String BEGIN = "begin";
    private static final Pattern TRANSACTION_NAME = Pattern.compile(".*(transaction|tx)$", Pattern.CASE_INSENSITIVE);

    /**
     * @return вызов вида {@code transactionTemplate.execute(...)}, в лямбде или анонимном классе которого лежит
     * узел; пусто, если узел не в таком обратном вызове
     */
    public Optional<MethodCallExpr> enclosingCallback(Node node) {
        Node child = node;
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            boolean isCallbackBody = child instanceof LambdaExpr || child instanceof ObjectCreationExpr;
            if (isCallbackBody && current instanceof MethodCallExpr call && isCallback(call)
                    && call.getArguments().contains(child)) {
                return Optional.of(call);
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    /**
     * @return true, если метод сам задает транзакцию: аннотацией, как бин EJB либо вручную - через
     * setAutoCommit(false), getTransaction(...) или commit()
     */
    public boolean isTransactional(MethodDeclaration method) {
        if (TransactionalAnnotations.findEffective(method).isPresent()) {
            return true;
        }
        boolean isContainerManaged = method.getParentNode()
                .filter(TypeDeclaration.class::isInstance)
                .map(parent -> (TypeDeclaration<?>) parent)
                .filter(type -> Annotations.hasAny(type, CONTAINER_MANAGED))
                .isPresent();
        return isContainerManaged || hasManualTransaction(method);
    }

    /**
     * @return true, если код в этом месте выполняется в транзакции - своей либо открытой выше по цепочке вызовов
     */
    public boolean isInTransaction(Node node, CallGraph graph) {
        if (enclosingCallback(node).isPresent()) {
            return true;
        }
        Optional<MethodDeclaration> method = node.findAncestor(MethodDeclaration.class);
        if (method.isEmpty()) {
            return false;
        }
        // Метод анонимного класса или локального объекта выполняется внутри метода, в котором объявлен
        for (Optional<MethodDeclaration> outer = method; outer.isPresent();
             outer = outer.get().findAncestor(MethodDeclaration.class)) {
            if (isTransactional(outer.get())
                    || isAlwaysInTransaction(outer.get(), graph, CALLER_DEPTH, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }

    // transactionTemplate.execute(status -> ...), new TransactionTemplate(manager).executeWithoutResult(...)
    private boolean isCallback(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (CALLBACK_METHOD.matcher(name).matches()) {
            return true;
        }
        return TEMPLATE_METHODS.contains(name) && call.getScope().filter(TransactionScopes::isTemplate).isPresent();
    }

    private boolean isTemplate(Expression scope) {
        return LocalTypes.isAnyOf(scope, TEMPLATE_TYPES,
                () -> TEMPLATE_NAME.matcher(MethodCalls.receiverName(scope)).matches());
    }

    private boolean hasManualTransaction(MethodDeclaration method) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (MANUAL_START.contains(name)) {
                return true;
            }
            if (SET_AUTO_COMMIT.equals(name) && call.getArguments().size() == 1
                    && call.getArgument(0).isBooleanLiteralExpr()
                    && !call.getArgument(0).asBooleanLiteralExpr().getValue()) {
                return true;
            }
            // transaction.begin(), em.getTransaction().begin()
            if (BEGIN.equals(name) && call.getScope()
                    .filter(scope -> TRANSACTION_NAME.matcher(MethodCalls.receiverName(scope)).matches()
                            || MANUAL_START.contains(MethodCalls.receiverName(scope)))
                    .isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Транзакция действует на всю цепочку вызовов, а не только на прямого вызывающего
     *
     * @return true, если каждая цепочка вызовов метода проходит через транзакцию
     */
    private boolean isAlwaysInTransaction(MethodDeclaration method, CallGraph graph, int depth,
                                          Set<MethodDeclaration> seen) {
        List<CallGraph.Call> callers = graph.callsTo(method);
        // Метод без вызовов в проекте вызывает кто-то снаружи; замкнутая цепочка транзакцию не открывает
        if (callers.isEmpty() || depth == 0 || !seen.add(method)) {
            return false;
        }
        return callers.stream().allMatch(call -> enclosingCallback(call.site()).isPresent()
                || isTransactional(call.caller())
                || isAlwaysInTransaction(call.caller(), graph, depth - 1, seen));
    }
}
