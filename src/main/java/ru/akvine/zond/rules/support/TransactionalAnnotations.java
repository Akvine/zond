package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@UtilityClass
public class TransactionalAnnotations {
    private final static String TRANSACTIONAL = "Transactional";
    private final static String READ_ONLY = "readOnly";

    // Режимы, при которых метод обязан получить собственное поведение, а не просто присоединиться к транзакции
    private final static Set<String> OWN_BEHAVIOR_PROPAGATIONS =
            Set.of("REQUIRES_NEW", "NESTED", "NOT_SUPPORTED", "NEVER");

    // rollbackFor / rollbackForClassName - spring, rollbackOn - jakarta / javax
    private final static Set<String> ROLLBACK_ATTRIBUTES = Set.of("rollbackFor", "rollbackForClassName", "rollbackOn");

    // Сравниваем по простому имени, чтобы поймать и @Transactional, и полное имя (spring / jakarta / javax)
    public Optional<AnnotationExpr> find(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .filter(annotation -> TRANSACTIONAL.equals(annotation.getName().getIdentifier()))
                .findFirst();
    }

    public boolean isPresent(NodeWithAnnotations<?> node) {
        return find(node).isPresent();
    }

    /**
     * Аннотация на методе полностью заменяет аннотацию на классе; аннотация на классе действует на public-методы.
     *
     * @return аннотация, которая определяет транзакцию метода
     */
    public Optional<AnnotationExpr> findEffective(MethodDeclaration method) {
        Optional<AnnotationExpr> onMethod = find(method);
        if (onMethod.isPresent()) {
            return onMethod;
        }

        return method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?>)
                .map(parent -> (TypeDeclaration<?>) parent)
                .filter(type -> method.isPublic() || isInterface(type))
                .flatMap(TransactionalAnnotations::find);
    }

    public boolean isReadOnly(AnnotationExpr annotation) {
        return annotation.isNormalAnnotationExpr()
                && annotation.asNormalAnnotationExpr().getPairs().stream()
                .anyMatch(pair -> READ_ONLY.equals(pair.getNameAsString())
                        && pair.getValue().isBooleanLiteralExpr()
                        && pair.getValue().asBooleanLiteralExpr().getValue());
    }

    private boolean isInterface(TypeDeclaration<?> type) {
        return type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface();
    }

    /**
     * @return режим вроде REQUIRES_NEW, если он задан в аннотации (propagation у spring, value у jakarta / javax)
     */
    public Optional<String> findOwnBehaviorPropagation(AnnotationExpr annotation) {
        return memberValues(annotation).stream()
                .map(TransactionalAnnotations::lastIdentifier)
                .filter(OWN_BEHAVIOR_PROPAGATIONS::contains)
                .findFirst();
    }

    /**
     * @return true, если в аннотации явно задано, при каких исключениях откатывать транзакцию
     */
    public boolean hasRollbackRule(AnnotationExpr annotation) {
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
