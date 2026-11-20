package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.loaders.ProjectDeclarations;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Аннотации сравниваются по простому имени, чтобы поймать и короткую, и полную запись.
 * <p>
 * Составная аннотация проекта считается наравне с теми, которые она несет: класс с {@code @UseCase}, объявленной
 * как {@code @Component @interface UseCase}, для Spring такой же бин, как класс с {@code @Component}
 */
@UtilityClass
public class Annotations {

    /**
     * @return аннотация, записанная на самом узле. Составные аннотации здесь не раскрываются: вернуть пришлось бы
     * узел из чужого файла, а по найденной аннотации правила берут и место находки
     */
    public Optional<AnnotationExpr> find(NodeWithAnnotations<?> node, Set<String> names) {
        for (AnnotationExpr annotation : node.getAnnotations()) {
            if (names.contains(annotation.getName().getIdentifier())) {
                return Optional.of(annotation);
            }
        }
        return Optional.empty();
    }

    public Optional<AnnotationExpr> find(NodeWithAnnotations<?> node, String name) {
        return find(node, Set.of(name));
    }

    /**
     * @return аннотация с одним из имен, которую несет составная аннотация проекта на этом узле:
     * {@code @TransactionalService} -> {@code @Transactional(readOnly = true)}. Узел принадлежит объявлению
     * составной аннотации, поэтому годится для чтения параметров, но не как место находки
     */
    public Optional<AnnotationExpr> findCarried(NodeWithAnnotations<?> node, Set<String> names) {
        if (node.getAnnotations().isEmpty() || !(node instanceof Node owner)) {
            return Optional.empty();
        }
        ProjectDeclarations declarations = null;
        for (AnnotationExpr annotation : node.getAnnotations()) {
            if (declarations == null) {
                declarations = ProjectDeclarations.of(owner);
            }
            if (!declarations.declaresAnnotation(annotation.getName().getIdentifier())) {
                continue;
            }
            for (AnnotationExpr meta : declarations.metaAnnotations(annotation)) {
                if (names.contains(meta.getName().getIdentifier())) {
                    return Optional.of(meta);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return аннотация с одним из имен - записанная на узле либо та, которую несет составная аннотация проекта
     */
    public Optional<AnnotationExpr> findEffective(NodeWithAnnotations<?> node, Set<String> names) {
        Optional<AnnotationExpr> direct = find(node, names);
        return direct.isPresent() ? direct : findCarried(node, names);
    }

    public boolean has(NodeWithAnnotations<?> node, String name) {
        return hasAny(node, Set.of(name));
    }

    public boolean hasAny(NodeWithAnnotations<?> node, Set<String> names) {
        return find(node, names).isPresent() || findCarried(node, names).isPresent();
    }

    /**
     * @return простые имена аннотаций узла вместе с теми, которые несут составные аннотации проекта
     */
    public Set<String> names(NodeWithAnnotations<?> node) {
        Set<String> names = new HashSet<>();
        if (node.getAnnotations().isEmpty()) {
            return names;
        }
        ProjectDeclarations declarations = node instanceof Node owner ? ProjectDeclarations.of(owner) : null;
        for (AnnotationExpr annotation : node.getAnnotations()) {
            names.add(annotation.getName().getIdentifier());
            if (declarations != null && declarations.declaresAnnotation(annotation.getName().getIdentifier())) {
                declarations.metaAnnotations(annotation).forEach(meta -> names.add(meta.getName().getIdentifier()));
            }
        }
        return names;
    }
}
