package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.experimental.UtilityClass;

import java.util.Optional;

/**
 * Запросы Spring Data: аннотация @Query на методе репозитория и ее настройки
 */
@UtilityClass
public class Queries {
    private static final String QUERY = "Query";
    private static final String VALUE = "value";
    private static final String NATIVE_QUERY = "nativeQuery";
    private static final String TRUE = "true";
    private static final String REPOSITORY_SUFFIX = "Repository";
    private static final String REPOSITORY_DEFINITION = "RepositoryDefinition";
    // Глубина цепочки своих базовых интерфейсов репозитория
    private static final int MAX_DEPTH = 5;

    public Optional<AnnotationExpr> find(MethodDeclaration method) {
        return Annotations.find(method, QUERY);
    }

    /**
     * @return текст запроса; пусто, если он задан константой или выражением и по коду неизвестен
     */
    public Optional<String> text(AnnotationExpr query) {
        Optional<Expression> value = query.isSingleMemberAnnotationExpr()
                ? Optional.of(query.asSingleMemberAnnotationExpr().getMemberValue())
                : member(query, VALUE);
        return value.flatMap(StringLiterals::textOf);
    }

    public boolean isNative(AnnotationExpr query) {
        return member(query, NATIVE_QUERY).filter(value -> TRUE.equals(value.toString())).isPresent();
    }

    public Optional<Expression> member(AnnotationExpr annotation, String name) {
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
    public boolean isRepositoryInterface(ClassOrInterfaceDeclaration type) {
        return type.isInterface() && (Repositories.isRepository(type.getNameAsString())
                || type.getExtendedTypes().stream().anyMatch(parent -> parent.getNameAsString().endsWith(REPOSITORY_SUFFIX)));
    }

    /**
     * @return true для репозитория Spring Data: интерфейс наследует Repository библиотеки (JpaRepository,
     * CrudRepository) прямо либо через свой базовый интерфейс. Свой {@code Dao<T>} с методом findByFilter -
     * обычный интерфейс: запрос по имени его метода Spring не строит
     */
    public boolean isSpringDataRepository(ClassOrInterfaceDeclaration type, ProjectClasses classes) {
        return isSpringDataRepository(type, classes, MAX_DEPTH);
    }

    private boolean isSpringDataRepository(ClassOrInterfaceDeclaration type, ProjectClasses classes, int depth) {
        if (!type.isInterface()) {
            return false;
        }
        if (Annotations.has(type, REPOSITORY_DEFINITION)) {
            return true;
        }
        for (ClassOrInterfaceType parent : type.getExtendedTypes()) {
            Optional<ClassOrInterfaceDeclaration> own = classes.resolve(parent);
            if (own.isEmpty() ? parent.getNameAsString().endsWith(REPOSITORY_SUFFIX)
                    : depth > 0 && isSpringDataRepository(own.get(), classes, depth - 1)) {
                return true;
            }
        }
        return false;
    }
}
