package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import lombok.experimental.UtilityClass;

import java.util.Optional;

/**
 * Связь репозитория Spring Data с сущностью, которую он хранит: orderRepository -> OrderRepository -> Order
 */
@UtilityClass
public class RepositoryEntities {
    // interface OrderRepository extends BaseRepository<Order>, а тот - JpaRepository<T, Long>: глубже не бывает
    private static final int MAX_DEPTH = 3;

    /**
     * @param scope объект, на котором вызван метод: orderRepository
     * @return интерфейс репозитория, если он объявлен в проекте
     */
    public Optional<ClassOrInterfaceDeclaration> repositoryOf(Expression scope, ProjectClasses classes) {
        return LocalTypes.typeOf(scope).flatMap(classes::find).filter(ClassOrInterfaceDeclaration::isInterface);
    }

    /**
     * @return сущность, с которой работает репозиторий; пусто, если ее не удалось установить
     */
    public Optional<ClassOrInterfaceDeclaration> entityOf(Expression scope, ProjectClasses classes) {
        return repositoryOf(scope, classes).flatMap(repository -> entityOf(repository, classes, MAX_DEPTH));
    }

    /**
     * @param repository интерфейс репозитория
     * @return сущность, с которой он работает; пусто, если ее не удалось установить
     */
    public Optional<ClassOrInterfaceDeclaration> entityOf(ClassOrInterfaceDeclaration repository, ProjectClasses classes) {
        return entityOf(repository, classes, MAX_DEPTH);
    }

    // Сущность - первый параметр типа у Repository<Сущность, Ключ>, в том числе у своего базового интерфейса
    private Optional<ClassOrInterfaceDeclaration> entityOf(
            ClassOrInterfaceDeclaration repository, ProjectClasses classes, int depth) {
        for (ClassOrInterfaceType parent : repository.getExtendedTypes()) {
            Optional<Type> first = parent.getTypeArguments()
                    .filter(arguments -> !arguments.isEmpty())
                    .map(arguments -> arguments.get(0));
            if (first.isPresent() && first.get().isClassOrInterfaceType()) {
                Optional<ClassOrInterfaceDeclaration> entity = classes.resolve(first.get().asClassOrInterfaceType())
                        .filter(found -> !found.isInterface());
                if (entity.isPresent()) {
                    return entity;
                }
            }
            if (depth > 0) {
                Optional<ClassOrInterfaceDeclaration> inherited = classes.resolve(parent)
                        .filter(ClassOrInterfaceDeclaration::isInterface)
                        .flatMap(base -> entityOf(base, classes, depth - 1));
                if (inherited.isPresent()) {
                    return inherited;
                }
            }
        }
        return Optional.empty();
    }
}
