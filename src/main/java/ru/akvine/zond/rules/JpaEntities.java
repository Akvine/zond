package ru.akvine.zond.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@UtilityClass
class JpaEntities {
    private final static String ENTITY = "Entity";
    private final static String TRANSACTIONAL = "Transactional";
    private final static List<String> PERSISTENCE_PACKAGES =
            List.of("jakarta.persistence", "javax.persistence", "org.springframework.data", "org.hibernate");

    // OrderRepository, OrderDao, EntityManager
    private final static Pattern PERSISTENCE_TYPE = Pattern.compile(".*(Repository|Dao|DAO)$|^EntityManager$|^Session$");
    final static Set<String> RELATION_ANNOTATIONS =
            Set.of("OneToMany", "ManyToOne", "OneToOne", "ManyToMany", "ElementCollection");

    List<ClassOrInterfaceDeclaration> findEntities(CompilationUnit unit) {
        return unit.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> !type.isInterface() && Annotations.has(type, ENTITY))
                .toList();
    }

    /**
     * @return поля-связи с другими сущностями: @OneToMany, @ManyToOne и т.п.
     */
    List<FieldDeclaration> relationFields(ClassOrInterfaceDeclaration entity) {
        return entity.getFields().stream()
                .filter(field -> Annotations.hasAny(field, RELATION_ANNOTATIONS))
                .toList();
    }

    Set<String> relationNames(ClassOrInterfaceDeclaration entity) {
        return relationFields(entity).stream()
                .flatMap(field -> field.getVariables().stream())
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.toSet());
    }

    /**
     * Типы не разрешаем, поэтому о том, что файл работает с БД, судим по косвенным признакам:
     * импорт JPA / Spring Data, зависимость-репозиторий или EntityManager, @Transactional.
     * Без этого любой getItems().size() пришлось бы считать обращением к связи сущности.
     */
    boolean isPersistenceCode(CompilationUnit unit) {
        boolean importsPersistence = unit.getImports().stream()
                .map(importDeclaration -> importDeclaration.getNameAsString())
                .anyMatch(name -> PERSISTENCE_PACKAGES.stream().anyMatch(name::contains));
        if (importsPersistence) {
            return true;
        }

        boolean usesRepository = unit.findAll(ClassOrInterfaceType.class).stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .anyMatch(type -> PERSISTENCE_TYPE.matcher(type).matches());
        return usesRepository || unit.findAll(AnnotationExpr.class).stream()
                .anyMatch(annotation -> TRANSACTIONAL.equals(annotation.getName().getIdentifier()));
    }

    /**
     * @return true, если у класса есть родитель: часть объявлений (@Id, @Version) может находиться в нем
     */
    boolean hasParent(ClassOrInterfaceDeclaration entity) {
        return !entity.getExtendedTypes().isEmpty();
    }
}
