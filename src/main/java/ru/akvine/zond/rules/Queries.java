package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import lombok.experimental.UtilityClass;

import java.util.Optional;

/**
 * Запросы Spring Data: аннотация @Query на методе репозитория и ее настройки
 */
@UtilityClass
class Queries {
    private static final String QUERY = "Query";
    private static final String VALUE = "value";
    private static final String NATIVE_QUERY = "nativeQuery";
    private static final String TRUE = "true";
    private static final String REPOSITORY_SUFFIX = "Repository";

    Optional<AnnotationExpr> find(MethodDeclaration method) {
        return Annotations.find(method, QUERY);
    }

    /**
     * @return текст запроса; пусто, если он задан константой или выражением и по коду неизвестен
     */
    Optional<String> text(AnnotationExpr query) {
        Optional<Expression> value = query.isSingleMemberAnnotationExpr()
                ? Optional.of(query.asSingleMemberAnnotationExpr().getMemberValue())
                : member(query, VALUE);
        return value.flatMap(StringLiterals::textOf);
    }

    boolean isNative(AnnotationExpr query) {
        return member(query, NATIVE_QUERY).filter(value -> TRUE.equals(value.toString())).isPresent();
    }

    Optional<Expression> member(AnnotationExpr annotation, String name) {
        if (!annotation.isNormalAnnotationExpr()) {
            return Optional.empty();
        }
        return annotation.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> name.equals(pair.getNameAsString()))
                .map(MemberValuePair::getValue)
                .findFirst();
    }

    /**
     * @return true для интерфейса репозитория: по имени (UserRepository, OrderDao) либо по предку (JpaRepository)
     */
    boolean isRepositoryInterface(ClassOrInterfaceDeclaration type) {
        return type.isInterface() && (Repositories.isRepository(type.getNameAsString())
                || type.getExtendedTypes().stream().anyMatch(parent -> parent.getNameAsString().endsWith(REPOSITORY_SUFFIX)));
    }
}
