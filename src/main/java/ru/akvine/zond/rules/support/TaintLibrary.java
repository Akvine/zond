package ru.akvine.zond.rules.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Справочник для отслеживания данных извне: откуда они приходят (методы запроса, аннотации параметров,
 * обработчики очередей), какие методы передают их дальше и какие обезвреживают.
 * <p>
 * Лежит в файле taint-library.properties рядом с классами приложения: новый фреймворк (Vert.x, gRPC, Micronaut)
 * добавляется без правки кода. Свой файл с дополнениями задается настройкой zond.taint.library - его записи
 * добавляются к встроенным. Имена методов, типов и аннотаций записываются без пакетов; разделы *-words - это
 * части имени: метод с таким словом в имени (encodeForHtml, sanitizeInput) считается очисткой
 */
public final class TaintLibrary {
    private static final String RESOURCE = "/taint-library.properties";
    private static final String SEPARATOR = "[,\\s]+";

    // Раздел -> имена; записи своего файла добавляются в те же множества
    private static final Map<String, Set<String>> ENTRIES = new ConcurrentHashMap<>();

    static {
        try (InputStream stream = TaintLibrary.class.getResourceAsStream(RESOURCE)) {
            if (stream != null) {
                read(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            // Без справочника отслеживание работает, только знает меньше источников
        }
    }

    private TaintLibrary() {
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
            throw new IllegalArgumentException("Не удалось прочитать справочник источников данных " + file, exception);
        }
    }

    /**
     * @return имена раздела; множество живое - дополнения из своего файла видны в нем сразу
     */
    public static Set<String> names(String section) {
        return Collections.unmodifiableSet(section(section));
    }

    /**
     * @return true, если в имени есть одно из слов раздела, без учета регистра: encodeForHTML для "html"
     */
    public static boolean containsWord(String section, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return section(section).stream().anyMatch(word -> lower.contains(word.toLowerCase(Locale.ROOT)));
    }

    private static Set<String> section(String section) {
        return ENTRIES.computeIfAbsent(section, key -> ConcurrentHashMap.newKeySet());
    }

    private static void read(Reader reader) throws IOException {
        Properties properties = new Properties();
        properties.load(reader);
        for (String section : properties.stringPropertyNames()) {
            section(section).addAll(Arrays.stream(properties.getProperty(section).split(SEPARATOR))
                    .filter(name -> !name.isBlank())
                    .toList());
        }
    }
}
