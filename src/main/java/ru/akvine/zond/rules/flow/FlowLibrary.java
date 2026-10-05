package ru.akvine.zond.rules.flow;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Справочник методов JDK и библиотек, поведение которых анализ потока данных знает заранее.
 * Лежит в файле flow-library.properties рядом с классами приложения: пополнять его можно без правки кода.
 * Свой файл с дополнениями задается настройкой zond.flow.library - его записи добавляются к встроенным.
 * <p>
 * Методы записаны именами без классов: тип объекта при разборе известен не всегда, а имена вроде
 * requireNonNull или isBlank значат одно и то же во всех библиотеках.
 */
public final class FlowLibrary {
    private static final String RESOURCE = "/flow-library.properties";
    private static final String SEPARATOR = "[,\\s]+";

    private static final String ASSERTS_NOT_NULL = "asserts-not-null";
    private static final String TRUE_FOR_NULL = "true-for-null";
    private static final String FALSE_FOR_NULL = "false-for-null";
    private static final String NEVER_RETURNS = "never-returns";
    private static final String ADDS = "adds-element";
    private static final String QUERY_PREFIXES = "query-prefixes";
    private static final String COLLECTIONS = "collections";

    // Раздел -> имена
    private static final Map<String, Set<String>> ENTRIES = new ConcurrentHashMap<>();

    static {
        try (InputStream stream = FlowLibrary.class.getResourceAsStream(RESOURCE)) {
            if (stream != null) {
                read(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            // Без справочника анализ работает, только знает о библиотеках меньше
        }
    }

    private FlowLibrary() {
    }

    /**
     * Добавляет записи из своего файла к встроенным
     *
     * @throws IllegalArgumentException если файл не удалось прочитать
     */
    public static void extend(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            read(reader);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Не удалось прочитать справочник библиотек " + file, exception);
        }
    }

    /**
     * @return true, если после вызова аргумент точно не null: Objects.requireNonNull(x), Assert.notNull(x, ...)
     */
    public static boolean assertsNotNull(String method) {
        return has(ASSERTS_NOT_NULL, method);
    }

    /**
     * @return true, если метод-проверка возвращает true для null: isBlank(null), isEmpty(null)
     */
    public static boolean isTrueForNull(String method) {
        return has(TRUE_FOR_NULL, method);
    }

    /**
     * @return true, если метод-проверка возвращает false для null: hasText(null), nonNull(null)
     */
    public static boolean isFalseForNull(String method) {
        return has(FALSE_FOR_NULL, method);
    }

    /**
     * @return true, если метод не возвращает управление: System.exit(...), fail(...)
     */
    public static boolean neverReturns(String method) {
        return has(NEVER_RETURNS, method);
    }

    /**
     * @return true, если после вызова в коллекции есть хотя бы один элемент: add, put, push
     */
    public static boolean adds(String method) {
        return has(ADDS, method);
    }

    /**
     * @return true, если метод только читает объект и ничего в нем не меняет: getName(), isEmpty(), contains(x).
     * Определяется по началу имени; после любого другого метода о состоянии объекта ничего не известно
     */
    public static boolean isQuery(String method) {
        return ENTRIES.getOrDefault(QUERY_PREFIXES, Set.of()).stream().anyMatch(method::startsWith);
    }

    /**
     * @return true для типов-коллекций, которые создаются пустыми: new ArrayList<>(), new HashMap<>()
     */
    public static boolean isCollection(String type) {
        return has(COLLECTIONS, type);
    }

    private static boolean has(String section, String name) {
        return ENTRIES.getOrDefault(section, Set.of()).contains(name);
    }

    private static void read(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        for (String section : properties.stringPropertyNames()) {
            Set<String> names = ENTRIES.computeIfAbsent(section, key -> ConcurrentHashMap.newKeySet());
            names.addAll(new HashSet<>(Arrays.stream(properties.getProperty(section).split(SEPARATOR))
                    .filter(name -> !name.isBlank())
                    .toList()));
        }
    }
}
