package ru.akvine.zond.loaders;

import org.springframework.stereotype.Component;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class FileSystemConfigLoader implements ConfigLoader {
    private static final Pattern CONFIG_NAME = Pattern.compile("^(application|bootstrap).*\\.(properties|yml|yaml)$");
    private static final String PROPERTIES_EXTENSION = ".properties";

    // Каталоги сборки: там лежат копии тех же файлов
    private static final Set<String> BUILD_DIRECTORIES = Set.of("build", "target", "out", "node_modules");

    // key=value, key: value, key value
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s*([^=:\\s]+)\\s*[=:]?\\s*(.*)$");

    // Отступ, ключ и значение одной строки YAML
    private static final Pattern YAML_LINE = Pattern.compile("^(\\s*)([^\\s:#\\-][^:]*):(?:\\s+(.*))?$");
    private static final String YAML_DOCUMENT_SEPARATOR = "---";
    private static final Set<String> YAML_BLOCK_MARKERS = Set.of("|", ">", "|-", ">-");

    /**
     * Ключ YAML на пути к текущей строке вместе с его отступом
     */
    private record YamlKey(int indent, String name) {
    }

    @Override
    public List<ConfigFile> load(Path root, Predicate<Path> included) {
        if (!Files.exists(root)) {
            return List.of();
        }

        List<ConfigFile> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(this::isConfig).filter(included).sorted().toList()) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                boolean isProperties = path.getFileName().toString().endsWith(PROPERTIES_EXTENSION);
                files.add(new ConfigFile(path, isProperties ? parseProperties(lines) : parseYaml(lines)));
            }
        } catch (IOException exception) {
            // Нечитаемый файл настроек не должен ронять проверку кода
            return files;
        }
        return files;
    }

    private boolean isConfig(Path path) {
        for (Path part : path) {
            if (BUILD_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        return CONFIG_NAME.matcher(path.getFileName().toString()).matches();
    }

    private List<ConfigProperty> parseProperties(List<String> lines) {
        List<ConfigProperty> properties = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            Matcher matcher = PROPERTY_LINE.matcher(line);
            if (matcher.matches()) {
                properties.add(new ConfigProperty(matcher.group(1), matcher.group(2).trim(), index + 1));
            }
        }
        return properties;
    }

    // Упрощенный разбор: вложенные ключи со скалярными значениями. Списки и многострочные блоки пропускаются -
    // для проверок нужны только свойства вида spring.jpa.hibernate.ddl-auto
    private List<ConfigProperty> parseYaml(List<String> lines) {
        List<ConfigProperty> properties = new ArrayList<>();
        Deque<YamlKey> path = new ArrayDeque<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = stripComment(lines.get(index));
            if (line.trim().equals(YAML_DOCUMENT_SEPARATOR)) {
                path.clear();
                continue;
            }

            Matcher matcher = YAML_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            int indent = matcher.group(1).length();
            while (!path.isEmpty() && path.peekLast().indent() >= indent) {
                path.removeLast();
            }

            String name = matcher.group(2).trim();
            String value = matcher.group(3) == null ? "" : unquote(matcher.group(3).trim());
            if (value.isEmpty() || YAML_BLOCK_MARKERS.contains(value)) {
                path.addLast(new YamlKey(indent, name));
            } else {
                properties.add(new ConfigProperty(fullKey(path, name), value, index + 1));
            }
        }
        return properties;
    }

    private String fullKey(Deque<YamlKey> path, String name) {
        StringBuilder key = new StringBuilder();
        for (Iterator<YamlKey> iterator = path.iterator(); iterator.hasNext(); ) {
            key.append(iterator.next().name()).append('.');
        }
        return key.append(name).toString();
    }

    // Комментарий в YAML начинается с # в начале строки либо после пробела
    private String stripComment(String line) {
        if (line.trim().startsWith("#")) {
            return "";
        }
        int comment = line.indexOf(" #");
        return comment < 0 ? line : line.substring(0, comment);
    }

    private String unquote(String value) {
        boolean quoted = value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")));
        return quoted ? value.substring(1, value.length() - 1) : value;
    }
}
