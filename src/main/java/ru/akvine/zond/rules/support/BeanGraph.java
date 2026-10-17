package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import ru.akvine.zond.models.SourceFile;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Зависимости Spring-бинов проекта: какой бин какие внедряет. Бины различаются по простому имени класса;
 * зависимость через интерфейс ведет к его реализации, если она в проекте одна
 */
public final class BeanGraph {
    private final Map<String, ClassOrInterfaceDeclaration> beans = new HashMap<>();
    // Одно имя у двух бинов: какой из них внедряется, по имени не понять
    private final Set<String> ambiguous = new HashSet<>();
    private final Map<String, Set<String>> implementations = new HashMap<>();
    private final Map<String, Set<String>> dependencies = new HashMap<>();

    private BeanGraph() {
    }

    public static BeanGraph of(List<SourceFile> sources) {
        BeanGraph graph = new BeanGraph();
        for (SourceFile source : sources) {
            for (ClassOrInterfaceDeclaration type : source.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!SpringBeans.isBean(type)) {
                    continue;
                }
                if (graph.beans.putIfAbsent(type.getNameAsString(), type) != null) {
                    graph.ambiguous.add(type.getNameAsString());
                }
                type.getImplementedTypes().forEach(parent -> graph.implementations
                        .computeIfAbsent(parent.getNameAsString(), key -> new TreeSet<>())
                        .add(type.getNameAsString()));
            }
        }
        return graph;
    }

    public boolean isBean(String name) {
        return beans.containsKey(name) && !ambiguous.contains(name);
    }

    /**
     * @return бин проекта, который внедрится вместо типа: сам класс либо единственная реализация интерфейса
     */
    public Optional<String> resolve(String typeName) {
        if (beans.containsKey(typeName)) {
            return isBean(typeName) ? Optional.of(typeName) : Optional.empty();
        }
        Set<String> candidates = implementations.getOrDefault(typeName, Set.of());
        return candidates.size() == 1 ? Optional.of(candidates.iterator().next()).filter(this::isBean) : Optional.empty();
    }

    /**
     * Все зависимости бина, в том числе отложенные (@Lazy, ObjectProvider): цикл через них остается циклом
     */
    public Set<String> dependenciesOf(String bean) {
        Set<String> known = dependencies.get(bean);
        if (known != null) {
            return known;
        }
        Set<String> found = new TreeSet<>();
        ClassOrInterfaceDeclaration type = beans.get(bean);
        if (type != null) {
            for (SpringBeans.Dependency dependency : SpringBeans.findDependencies(type)) {
                resolve(dependency.type()).ifPresent(found::add);
            }
        }
        dependencies.put(bean, Collections.unmodifiableSet(found));
        return dependencies.get(bean);
    }

    /**
     * @return цикл to -> from -> ... -> to, если from через свои зависимости снова приходит к to; иначе пусто
     */
    public Optional<List<String>> pathBack(String from, String to) {
        Map<String, String> previous = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>(List.of(from));
        Set<String> visited = new HashSet<>(List.of(from));
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (current.equals(to)) {
                Deque<String> cycle = new ArrayDeque<>();
                for (String step = current; step != null; step = previous.get(step)) {
                    cycle.addFirst(step);
                }
                cycle.addFirst(to);
                return Optional.of(List.copyOf(cycle));
            }
            for (String next : dependenciesOf(current)) {
                if (visited.add(next)) {
                    previous.put(next, current);
                    queue.addLast(next);
                }
            }
        }
        return Optional.empty();
    }
}
