package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Как сущность JPA ложится на таблицу: имя таблицы и колонки ее полей.
 * Сущности и поля, для которых это по коду не видно, пропускаются: сверять их со схемой нельзя.
 */
@UtilityClass
public class EntityMapping {
    private static final String ENTITY = "Entity";
    private static final String TABLE = "Table";
    private static final String COLUMN = "Column";
    private static final String JOIN_COLUMN = "JoinColumn";
    private static final String NAME = "name";
    private static final String MAPPED_BY = "mappedBy";
    private static final String ID_SUFFIX = "_id";

    // С этими аннотациями сущность читает не одну свою таблицу: таблицу предка, несколько таблиц, запрос
    private static final Set<String> UNUSUAL_ENTITY = Set.of(
            "SecondaryTable", "SecondaryTables", "Subselect", "Inheritance", "DiscriminatorValue", "Synchronize");

    // Поля, у которых нет своей колонки в таблице сущности либо имя колонки задается не здесь
    private static final Set<String> WITHOUT_OWN_COLUMN = Set.of(
            "Transient", "OneToMany", "ManyToMany", "ElementCollection", "Embedded", "EmbeddedId", "Formula",
            "JoinTable", "JoinColumns", "MapsId", "Any", "ManyToAny", "JoinFormula", "AttributeOverride",
            "AttributeOverrides", "CollectionTable");
    private static final Set<String> TO_ONE = Set.of("ManyToOne", "OneToOne");
    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Map", "Collection", "SortedSet", "SortedMap");
    private static final Set<String> ON_ACCESSOR = Set.of("Id", "Column", "JoinColumn", "ManyToOne", "OneToMany", "EmbeddedId");
    private static final String EMBEDDABLE = "Embeddable";

    /**
     * Поле сущности и колонка, в которой оно хранится
     *
     * @param mapping аннотация @Column либо @JoinColumn, если она есть
     */
    public record MappedField(FieldDeclaration field, VariableDeclarator variable, String column, Optional<AnnotationExpr> mapping) {
    }

    /**
     * @return имя таблицы сущности; пусто, если сущность хранится необычно и сверять ее нельзя
     */
    public Optional<String> tableName(ClassOrInterfaceDeclaration entity, ProjectClasses classes) {
        if (!Annotations.has(entity, ENTITY) || Annotations.hasAny(entity, UNUSUAL_ENTITY)) {
            return Optional.empty();
        }
        // Аннотации стоят на геттерах: колонки задаются там, и поля о них ничего не говорят
        boolean propertyAccess = entity.getMethods().stream().anyMatch(method -> Annotations.hasAny(method, ON_ACCESSOR));
        if (propertyAccess) {
            return Optional.empty();
        }
        Optional<String> explicit = Annotations.find(entity, TABLE).flatMap(table -> member(table, NAME));
        if (explicit.isPresent()) {
            return explicit;
        }
        // Наследник другой сущности без своей таблицы хранится в таблице предка
        boolean extendsEntity = classes.parent(entity).filter(parent -> Annotations.has(parent, ENTITY)).isPresent();
        if (extendsEntity || Annotations.has(entity, TABLE) && explicit.isEmpty() && hasNameExpression(entity)) {
            return Optional.empty();
        }
        return Optional.of(snakeCase(entity.getNameAsString()));
    }

    /**
     * @return поля сущности, у которых есть своя колонка, вместе с ее именем
     */
    public List<MappedField> fields(ClassOrInterfaceDeclaration entity, ProjectClasses classes) {
        List<MappedField> fields = new ArrayList<>();
        for (FieldDeclaration field : entity.getFields()) {
            if (field.isStatic() || field.isTransient() || Annotations.hasAny(field, WITHOUT_OWN_COLUMN)) {
                continue;
            }
            String type = LocalTypes.typeName(field.getElementType());
            boolean embeddable = classes.find(type).filter(found -> Annotations.has(found, EMBEDDABLE)).isPresent();
            if (COLLECTIONS.contains(type) || embeddable) {
                continue;
            }
            boolean toOne = Annotations.hasAny(field, TO_ONE);
            // Обратная сторона связи один-к-одному: колонка лежит в другой таблице
            boolean inverse = Annotations.find(field, TO_ONE).flatMap(relation -> member(relation, MAPPED_BY)).isPresent();
            if (inverse) {
                continue;
            }
            Optional<AnnotationExpr> mapping = Annotations.find(field, toOne ? JOIN_COLUMN : COLUMN);
            for (VariableDeclarator variable : field.getVariables()) {
                columnName(mapping, variable.getNameAsString(), toOne)
                        .ifPresent(column -> fields.add(new MappedField(field, variable, column, mapping)));
            }
        }
        return fields;
    }

    /**
     * @return значение строкового члена аннотации; пусто, если члена нет или он задан не литералом
     */
    public Optional<String> member(AnnotationExpr annotation, String name) {
        return Queries.member(annotation, name).flatMap(StringLiterals::textOf).filter(text -> !text.isBlank());
    }

    /**
     * @return orderItem -> order_item: так Spring Boot по умолчанию называет таблицы и колонки
     */
    public String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    // Имя задано константой или выражением - какое оно, по коду неизвестно: такое поле не сверяется
    private Optional<String> columnName(Optional<AnnotationExpr> mapping, String field, boolean toOne) {
        Optional<Expression> declared = mapping.flatMap(annotation -> Queries.member(annotation, NAME));
        if (declared.isPresent()) {
            return declared.flatMap(StringLiterals::textOf).filter(text -> !text.isBlank());
        }
        return Optional.of(snakeCase(field) + (toOne ? ID_SUFFIX : ""));
    }

    private boolean hasNameExpression(ClassOrInterfaceDeclaration entity) {
        return Annotations.find(entity, TABLE).flatMap(table -> Queries.member(table, NAME)).isPresent();
    }
}
