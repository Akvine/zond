package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Работа с данными в Jmix: DataManager и цепочки загрузки dataManager.load(...).query(...).list()
 */
@UtilityClass
public class Jmix {
    public static final String UNCONSTRAINED_DATA_MANAGER = "UnconstrainedDataManager";
    private static final Set<String> DATA_MANAGERS = Set.of("DataManager", UNCONSTRAINED_DATA_MANAGER);
    // Если тип определить не удалось: dataManager, unconstrainedDataManager, systemDataManager
    private static final Pattern DATA_MANAGER_NAME = Pattern.compile("(?i).*datamanager$");

    private static final Set<String> LOAD_METHODS = Set.of("load", "loadValue", "loadValues");
    // Вызовы, которые заканчивают цепочку загрузки и выполняют запрос
    private static final Set<String> FETCH_METHODS = Set.of("one", "optional", "list");
    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Collection", "Iterable");
    private static final Set<String> SHORT_CIRCUIT = Set.of("findFirst", "findAny");

    /**
     * @return true, если метод вызван на DataManager
     */
    public boolean isDataManager(Expression scope) {
        return LocalTypes.isAnyOf(scope, DATA_MANAGERS,
                () -> DATA_MANAGER_NAME.matcher(MethodCalls.receiverName(scope)).matches());
    }

    /**
     * @return true для вызова вида dataManager.save(...), dataManager.remove(...)
     */
    public boolean isDataManagerCall(MethodCallExpr call, Set<String> methods) {
        return methods.contains(call.getNameAsString()) && call.getScope().filter(Jmix::isDataManager).isPresent();
    }

    /**
     * @return true, если повторение только кажется поэлементным: цикл идет по пачкам (каждый шаг - один запрос
     * на всю пачку) либо стрим останавливается на первом же элементе (findFirst, findAny)
     */
    public boolean isBatchOrSingleStep(Node node) {
        Optional<Node> iteration = Loops.enclosingIteration(node);
        if (iteration.isEmpty()) {
            return false;
        }
        if (iteration.get() instanceof ForEachStmt loop) {
            return COLLECTIONS.contains(LocalTypes.typeName(loop.getVariable().getCommonType()));
        }
        if (!(iteration.get() instanceof LambdaExpr lambda)) {
            return false;
        }
        // ...stream().map(x -> load(x)).findFirst(): поднимаемся по цепочке от вызова, которому отдана лямбда
        Node current = lambda.getParentNode().orElse(null);
        while (current instanceof MethodCallExpr call) {
            if (SHORT_CIRCUIT.contains(call.getNameAsString())) {
                return true;
            }
            Node parent = call.getParentNode().orElse(null);
            boolean chained = parent instanceof MethodCallExpr outer && outer.getScope().filter(scope -> scope == call).isPresent();
            current = chained ? parent : null;
        }
        return false;
    }

    /**
     * @param call вызов, который может завершать цепочку: ...list(), ...one(), ...optional()
     * @return вся цепочка от dataManager.load(...) до этого вызова, если он выполняет загрузку через DataManager
     */
    public Optional<List<MethodCallExpr>> loadChain(MethodCallExpr call) {
        if (!FETCH_METHODS.contains(call.getNameAsString())) {
            return Optional.empty();
        }
        List<MethodCallExpr> chain = new ArrayList<>();
        MethodCallExpr current = call;
        while (true) {
            chain.add(current);
            Optional<Expression> scope = current.getScope().map(Nodes::unwrap);
            if (scope.isEmpty() || !scope.get().isMethodCallExpr()) {
                break;
            }
            current = scope.get().asMethodCallExpr();
        }
        MethodCallExpr root = chain.get(chain.size() - 1);
        return isDataManagerCall(root, LOAD_METHODS) ? Optional.of(chain) : Optional.empty();
    }
}
