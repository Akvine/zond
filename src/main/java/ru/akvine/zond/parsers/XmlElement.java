package ru.akvine.zond.parsers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Элемент разобранного XML вместе со строкой файла
 *
 * @param name       имя без префикса пространства имен
 * @param attributes атрибуты; имена тоже без префикса
 * @param text       собственный текст элемента без пробелов по краям
 * @param line       строка, на которой закрывается открывающий тег
 */
public record XmlElement(String name, Map<String, String> attributes, String text, int line, List<XmlElement> children) {

    /**
     * @return значение атрибута либо пустая строка
     */
    public String attribute(String attributeName) {
        return attributes.getOrDefault(attributeName, "");
    }

    public List<XmlElement> children(String childName) {
        return children.stream().filter(child -> child.name().equals(childName)).toList();
    }

    public Optional<XmlElement> child(String childName) {
        return children.stream().filter(child -> child.name().equals(childName)).findFirst();
    }

    /**
     * @return текст вложенного элемента либо пустая строка
     */
    public String childText(String childName) {
        return child(childName).map(XmlElement::text).orElse("");
    }

    /**
     * @return все вложенные элементы на любой глубине, в порядке следования в файле
     */
    public List<XmlElement> descendants() {
        List<XmlElement> found = new ArrayList<>();
        collect(found);
        return found;
    }

    public List<XmlElement> descendants(String descendantName) {
        return descendants().stream().filter(element -> element.name().equals(descendantName)).toList();
    }

    private void collect(List<XmlElement> found) {
        for (XmlElement child : children) {
            found.add(child);
            child.collect(found);
        }
    }
}
