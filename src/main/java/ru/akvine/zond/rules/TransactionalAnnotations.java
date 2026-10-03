package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@UtilityClass
class TransactionalAnnotations {
    private final static String TRANSACTIONAL = "Transactional";

    // Режимы, при которых метод обязан получить собственное поведение, а не просто присоединиться к транзакции
    private final static Set<String> OWN_BEHAVIOR_PROPAGATIONS =
            Set.of("REQUIRES_NEW", "NESTED", "NOT_SUPPORTED", "NEVER");

    // rollbackFor / rollbackForClassName - spring, rollbackOn - jakarta / javax
    private final static Set<String> ROLLBACK_ATTRIBUTES = Set.of("rollbackFor", "rollbackForClassName", "rollbackOn");

    // Сравниваем по простому имени, чтобы поймать и @Transactional, и полное имя (spring / jakarta / javax)
    Optional<AnnotationExpr> find(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .filter(annotation -> TRANSACTIONAL.equals(annotation.getName().getIdentifier()))
                .findFirst();
    }

    boolean isPresent(NodeWithAnnotations<?> node) {
        return find(node).isPresent();
    }

    /**
     * @return режим вроде REQUIRES_NEW, если он задан в аннотации (propagation у spring, value у jakarta / javax)
     */
    Optional<String> findOwnBehaviorPropagation(AnnotationExpr annotation) {
        return memberValues(annotation).stream()
                .map(TransactionalAnnotations::lastIdentifier)
                .filter(OWN_BEHAVIOR_PROPAGATIONS::contains)
                .findFirst();
    }

    /**
     * @return true, если в аннотации явно задано, при каких исключениях откатывать транзакцию
     */
    boolean hasRollbackRule(AnnotationExpr annotation) {
        return annotation.isNormalAnnotationExpr()
                && annotation.asNormalAnnotationExpr().getPairs().stream()
                .anyMatch(pair -> ROLLBACK_ATTRIBUTES.contains(pair.getNameAsString()));
    }

    private List<Expression> memberValues(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return List.of(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (annotation.isNormalAnnotationExpr()) {
            return annotation.asNormalAnnotationExpr().getPairs().stream()
                    .map(pair -> pair.getValue())
                    .toList();
        }
        return List.of();
    }

    // Propagation.REQUIRES_NEW -> REQUIRES_NEW
    private String lastIdentifier(Expression expression) {
        if (expression.isFieldAccessExpr()) {
            return expression.asFieldAccessExpr().getNameAsString();
        }
        if (expression.isNameExpr()) {
            return expression.asNameExpr().getNameAsString();
        }
        return "";
    }
}
