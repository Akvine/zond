package ru.akvine.zond.models;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;

import java.lang.ref.SoftReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Разобранный исходный файл: путь + AST.
 * <p>
 * Узлы файла по типам запоминаются: одни и те же вызовы, присваивания и объявления просят сотни правил, и обходить
 * ради каждого все дерево заново - самая дорогая часть проверки. Дерево правила не меняют, поэтому список,
 * собранный один раз, остается верным до конца проверки. По той же причине запоминается и текст файла
 */
public final class SourceFile {
    private final Path path;
    private final CompilationUnit unit;

    // Тип, о котором спросили, -> его узлы в порядке обхода дерева. Заполняется из нескольких потоков
    private final Map<Class<?>, List<? extends Node>> nodesByType = new ConcurrentHashMap<>();
    // Все узлы файла в том порядке, в каком их обходит unit.findAll(...)
    private volatile List<Node> nodes;
    // Текст занимает память, сравнимую с самим файлом: когда ее не хватает, он освобождается и печатается заново
    private volatile SoftReference<String> text = new SoftReference<>(null);

    public SourceFile(Path path, CompilationUnit unit) {
        this.path = path;
        this.unit = unit;
    }

    public Path path() {
        return path;
    }

    public CompilationUnit unit() {
        return unit;
    }

    /**
     * То же, что {@code unit().findAll(type)}, но дерево обходится один раз на файл, а не при каждом вызове
     *
     * @return узлы файла заданного типа и его наследников в порядке обхода дерева; список новый, его можно менять
     */
    public <N extends Node> List<N> findAll(Class<N> type) {
        return new ArrayList<>(nodesOf(type));
    }

    /**
     * @return узлы файла заданного типа, которые проходят условие
     */
    public <N extends Node> List<N> findAll(Class<N> type, Predicate<N> condition) {
        List<N> found = new ArrayList<>();
        for (N node : nodesOf(type)) {
            if (condition.test(node)) {
                found.add(node);
            }
        }
        return found;
    }

    /**
     * То же, что {@code unit().toString()}, но файл печатается один раз, а не для каждого правила
     *
     * @return текст файла, напечатанный по дереву: с единым оформлением, каким бы ни был исходник
     */
    public String text() {
        String printed = text.get();
        if (printed == null) {
            printed = unit.toString();
            text = new SoftReference<>(printed);
        }
        return printed;
    }

    @SuppressWarnings("unchecked")
    private <N extends Node> List<N> nodesOf(Class<N> type) {
        return (List<N>) nodesByType.computeIfAbsent(type, this::collect);
    }

    private List<Node> collect(Class<?> type) {
        List<Node> found = new ArrayList<>();
        for (Node node : nodes()) {
            if (type.isInstance(node)) {
                found.add(node);
            }
        }
        return found;
    }

    private List<Node> nodes() {
        List<Node> known = nodes;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (nodes == null) {
                List<Node> collected = new ArrayList<>();
                unit.walk(Node.TreeTraversal.PREORDER, collected::add);
                nodes = collected;
            }
            return nodes;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SourceFile file && Objects.equals(path, file.path) && Objects.equals(unit, file.unit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, unit);
    }

    @Override
    public String toString() {
        return "SourceFile[path=" + path + "]";
    }
}
