package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Методы проекта, до которых доходит выполнение из заданного метода
 */
@UtilityClass
public class Reachability {

    /**
     * @param depth на сколько вызовов вглубь идти; 0 - только сам метод
     * @return сам метод и методы проекта, которые он вызывает напрямую или через другие методы
     */
    public Set<MethodDeclaration> within(CallGraph graph, MethodDeclaration start, int depth) {
        // Узлы дерева сравниваются по содержимому, а два одинаковых по тексту метода - разные методы
        Set<MethodDeclaration> reached = Collections.newSetFromMap(new IdentityHashMap<>());
        reached.add(start);
        List<MethodDeclaration> level = List.of(start);
        for (int step = 0; step < depth && !level.isEmpty(); step++) {
            List<MethodDeclaration> next = new ArrayList<>();
            for (MethodDeclaration method : level) {
                for (CallGraph.Call call : graph.callsFrom(method)) {
                    if (reached.add(call.target())) {
                        next.add(call.target());
                    }
                }
            }
            level = next;
        }
        return reached;
    }
}
