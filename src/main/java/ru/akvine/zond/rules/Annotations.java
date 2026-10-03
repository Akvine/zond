package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.Set;

/**
 * Аннотации сравниваются по простому имени, чтобы поймать и короткую, и полную запись
 */
@UtilityClass
class Annotations {

    Optional<AnnotationExpr> find(NodeWithAnnotations<?> node, Set<String> names) {
        return node.getAnnotations().stream()
                .filter(annotation -> names.contains(annotation.getName().getIdentifier()))
                .findFirst();
    }

    Optional<AnnotationExpr> find(NodeWithAnnotations<?> node, String name) {
        return find(node, Set.of(name));
    }

    boolean has(NodeWithAnnotations<?> node, String name) {
        return find(node, name).isPresent();
    }

    boolean hasAny(NodeWithAnnotations<?> node, Set<String> names) {
        return find(node, names).isPresent();
    }
}
