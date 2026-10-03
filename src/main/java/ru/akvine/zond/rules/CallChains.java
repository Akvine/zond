package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Поиск операции не в самом методе, а в тех, что он вызывает: напрямую или через несколько вызовов
 */
@UtilityClass
class CallChains {
    private static final String ARROW = " -> ";

    /**
     * @param operation что нашли: описание операции (restTemplate.getForObject)
     * @param path      через какие методы до нее дошли, от первого вызванного к тому, где она стоит
     */
    record Found(String operation, List<String> path) {

        // load -> fetch
        String chain() {
            return String.join(ARROW, path);
        }
    }

    /**
     * @param method    метод, в теле которого ищем операцию и вызовы дальше
     * @param depth     сколько вызовов можно пройти вглубь, считая сам метод: 1 - только его тело
     * @param skip      методы, в которые заходить не нужно: о них правило сообщает отдельно
     * @param operation распознает операцию в узле дерева
     */
    Optional<Found> find(
            CallGraph graph,
            MethodDeclaration method,
            int depth,
            Predicate<MethodDeclaration> skip,
            Function<Node, Optional<String>> operation) {
        Set<MethodDeclaration> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return find(graph, method, depth, skip, operation, visited);
    }

    private Optional<Found> find(
            CallGraph graph,
            MethodDeclaration method,
            int depth,
            Predicate<MethodDeclaration> skip,
            Function<Node, Optional<String>> operation,
            Set<MethodDeclaration> visited) {
        // Рекурсия и обход одного метода разными путями
        if (depth <= 0 || method.getBody().isEmpty() || !visited.add(method) || skip.test(method)) {
            return Optional.empty();
        }

        for (Node node : method.getBody().get().findAll(Node.class)) {
            Optional<String> found = operation.apply(node);
            if (found.isPresent()) {
                return Optional.of(new Found(found.get(), List.of(method.getNameAsString())));
            }
        }

        for (CallGraph.Call call : graph.callsFrom(method)) {
            Optional<Found> deeper = find(graph, call.target(), depth - 1, skip, operation, visited);
            if (deeper.isPresent()) {
                List<String> path = new ArrayList<>();
                path.add(method.getNameAsString());
                path.addAll(deeper.get().path());
                return Optional.of(new Found(deeper.get().operation(), path));
            }
        }
        return Optional.empty();
    }
}
