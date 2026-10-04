package ru.akvine.zond.parsers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Узел разобранного YAML: значение, словарь либо список - вместе со строкой файла.
 * Отсутствующий узел - тоже узел, только пустой: цепочку get("spec").get("template") можно писать без проверок.
 */
public final class YamlNode {
    private static final YamlNode MISSING = new YamlNode(0, null, Map.of(), List.of());

    private final int line;
    private final String text;
    private final Map<String, YamlNode> entries;
    private final List<YamlNode> items;

    private YamlNode(int line, String text, Map<String, YamlNode> entries, List<YamlNode> items) {
        this.line = line;
        this.text = text;
        this.entries = entries;
        this.items = items;
    }

    static YamlNode scalar(int line, String text) {
        return new YamlNode(line, text, Map.of(), List.of());
    }

    static YamlNode map(int line, Map<String, YamlNode> entries) {
        return new YamlNode(line, null, new LinkedHashMap<>(entries), List.of());
    }

    static YamlNode list(int line, List<YamlNode> items) {
        return new YamlNode(line, null, Map.of(), List.copyOf(items));
    }

    public static YamlNode missing() {
        return MISSING;
    }

    /**
     * @return строка файла: у значения словаря - строка его ключа, у элемента списка - строка, где он начинается
     */
    public int line() {
        return line;
    }

    public boolean exists() {
        return this != MISSING;
    }

    public boolean isScalar() {
        return text != null;
    }

    public boolean isMap() {
        return !entries.isEmpty();
    }

    public boolean isList() {
        return !items.isEmpty();
    }

    /**
     * @return значение без кавычек либо пустая строка, если узел - не значение
     */
    public String text() {
        return text == null ? "" : text;
    }

    /**
     * @return true, если значение равно ожидаемому без учета регистра: true, "True", yes пишут по-разному
     */
    public boolean is(String expected) {
        return text != null && text.trim().equalsIgnoreCase(expected);
    }

    /**
     * @return узел по ключу словаря либо отсутствующий узел
     */
    public YamlNode get(String key) {
        return entries.getOrDefault(key, MISSING);
    }

    public Map<String, YamlNode> entries() {
        return entries;
    }

    public List<YamlNode> items() {
        return items;
    }

    /**
     * Обходит все пары "ключ - значение" на любой глубине, включая вложенные в списки
     */
    public void visit(BiConsumer<String, YamlNode> visitor) {
        entries.forEach((key, node) -> {
            visitor.accept(key, node);
            node.visit(visitor);
        });
        items.forEach(item -> item.visit(visitor));
    }
}
