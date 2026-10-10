package ru.akvine.zond.loaders;

import org.springframework.stereotype.Component;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.parsers.YamlNode;
import ru.akvine.zond.parsers.YamlParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
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
            // Значение, перенесенное через \ в конце строки, продолжается на следующих: это одно свойство,
            // а не несколько. Иначе каждая строка продолжения стала бы отдельным ключом
            int firstLine = index;
            StringBuilder logical = new StringBuilder();
            while (endsWithContinuation(line) && index + 1 < lines.size()) {
                logical.append(line, 0, line.length() - 1);
                line = lines.get(++index).trim();
            }
            logical.append(line);
            Matcher matcher = PROPERTY_LINE.matcher(logical);
            if (matcher.matches()) {
                properties.add(new ConfigProperty(matcher.group(1), matcher.group(2).trim(), firstLine + 1));
            }
        }
        return properties;
    }

    // Нечетное число \ в конце строки - перенос; четное - экранированные обратные черты самого значения
    private boolean endsWithContinuation(String line) {
        int slashes = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }

    // Ключи вложенных словарей соединяются точкой, элементы списков получают индекс - так же свойства
    // называет Spring: app.servers[0].host
    private List<ConfigProperty> parseYaml(List<String> lines) {
        Optional<List<YamlNode>> documents = YamlParser.parse(lines);
        if (documents.isEmpty()) {
            // Файл с подстановками сборки (@project.version@) - не корректный YAML, но ключи в нем прочитать можно
            return parseYamlByLines(lines);
        }
        List<ConfigProperty> properties = new ArrayList<>();
        documents.get().forEach(document -> flatten("", document, properties));
        return properties;
    }

    private void flatten(String prefix, YamlNode node, List<ConfigProperty> properties) {
        if (node.isScalar()) {
            // Свойство без значения прежний разбор тоже пропускал: проверять в нем нечего
            if (!prefix.isEmpty() && !node.text().isEmpty()) {
                properties.add(new ConfigProperty(prefix, node.text(), node.line()));
            }
            return;
        }
        node.entries().forEach((key, value) -> flatten(prefix.isEmpty() ? key : prefix + "." + key, value, properties));
        for (int index = 0; index < node.items().size(); index++) {
            flatten(prefix + "[" + index + "]", node.items().get(index), properties);
        }
    }

    // Запасной разбор по строкам: вложенные ключи со скалярными значениями, без списков и многострочных блоков
    private List<ConfigProperty> parseYamlByLines(List<String> lines) {
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
