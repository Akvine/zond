package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * Классы, которые только хранят данные: сущности, DTO, модели. Имя такого класса может совпасть
 * с именем технического объекта (Client - покупатель, а не HTTP-клиент; Connection - связь, а не соединение),
 * но ввода-вывода в нем нет.
 */
@UtilityClass
public class DataObjects {
    private static final Set<String> DATA_ANNOTATIONS = Set.of(
            // JPA и Spring Data
            "Entity", "Table", "Embeddable", "MappedSuperclass", "Document",
            // Lombok: геттеры, сеттеры и builder нужны объектам с данными, а не сервисам
            "Data", "Value", "Getter", "Setter", "Builder", "SuperBuilder");

    public boolean isDataObject(TypeDeclaration<?> type) {
        return type instanceof RecordDeclaration
                || type instanceof EnumDeclaration
                || Annotations.hasAny(type, DATA_ANNOTATIONS);
    }
}
