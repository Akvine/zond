package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Свойства сущности так, как их понимают Spring Data и JPQL: имя из метода репозитория (AddressCity)
 * либо путь из запроса (address.city) сверяется с полями класса и его предков
 */
@UtilityClass
public class JpaProperties {
    private static final char EXPLICIT_SEPARATOR = '_';

    // Обращения, которые Hibernate понимает у любой сущности, каким бы ни было имя поля
    private static final Set<String> IMPLICIT = Set.of("id", "class");

    /**
     * @param path имя свойства из метода репозитория: Name, AddressCity, Address_City
     * @return false, только если свойства точно нет; если о классе известно не все - true
     */
    public boolean exists(ClassOrInterfaceDeclaration type, String path, ProjectClasses classes) {
        ProjectClasses.Properties properties = classes.properties(type);
        if (!properties.complete() || properties.has(uncapitalize(path)) || IMPLICIT.contains(uncapitalize(path))) {
            return true;
        }
        // Spring Data делит имя по заглавным буквам, начиная с самой длинной головы: AddressCity -> address.city
        for (int split = path.length() - 1; split > 0; split--) {
            boolean explicit = path.charAt(split) == EXPLICIT_SEPARATOR;
            if (!explicit && !Character.isUpperCase(path.charAt(split))) {
                continue;
            }
            String head = uncapitalize(path.substring(0, split));
            String tail = path.substring(explicit ? split + 1 : split);
            if (!properties.has(head) || tail.isEmpty()) {
                continue;
            }
            Optional<ClassOrInterfaceDeclaration> nested = typeOf(properties, head, classes);
            // Тип вложенного свойства лежит вне проекта: что в нем есть, неизвестно
            if (nested.isEmpty() || exists(nested.get(), tail, classes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param segments путь из запроса: address, city
     * @return первое звено пути, которого точно нет, либо пусто
     */
    public Optional<String> findMissing(ClassOrInterfaceDeclaration type, List<String> segments, ProjectClasses classes) {
        ClassOrInterfaceDeclaration current = type;
        for (String segment : segments) {
            ProjectClasses.Properties properties = classes.properties(current);
            if (!properties.complete() || IMPLICIT.contains(segment)) {
                return Optional.empty();
            }
            if (!properties.has(segment)) {
                return Optional.of(current.getNameAsString() + "." + segment);
            }
            Optional<ClassOrInterfaceDeclaration> nested = typeOf(properties, segment, classes);
            if (nested.isEmpty()) {
                return Optional.empty();
            }
            current = nested.get();
        }
        return Optional.empty();
    }

    /**
     * @return класс проекта, которым объявлено свойство; для коллекции - класс ее элемента
     */
    public Optional<ClassOrInterfaceDeclaration> typeOf(
            ClassOrInterfaceDeclaration type, List<String> segments, ProjectClasses classes) {
        Optional<ClassOrInterfaceDeclaration> current = Optional.of(type);
        for (String segment : segments) {
            if (current.isEmpty()) {
                return current;
            }
            current = typeOf(classes.properties(current.get()), segment, classes);
        }
        return current;
    }

    private Optional<ClassOrInterfaceDeclaration> typeOf(
            ProjectClasses.Properties properties, String name, ProjectClasses classes) {
        ProjectClasses.FieldType type = properties.types().get(name);
        if (type == null) {
            return Optional.empty();
        }
        return classes.find(type.elementName() == null ? type.name() : type.elementName())
                .filter(found -> !found.isInterface());
    }

    private String uncapitalize(String name) {
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
