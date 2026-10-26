package ru.akvine.zond.loaders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Зависимости проекта Gradle без запуска самого Gradle: координаты из build.gradle и build.gradle.kts, каталога
 * версий gradle/libs.versions.toml и свойств gradle.properties.
 * <p>
 * Понимаются записи 'group:name:version', group: 'g', name: 'n', version: 'v', ссылки libs.spring.boot.web на
 * каталог версий, версии из свойств ($springVersion, ${springVersion}) и BOM: platform('g:n:v') и плагин
 * org.springframework.boot с версией. Тестовые конфигурации (testImplementation и другие) пропускаются. Скрипты
 * Gradle - это код, и все его возможности здесь не повторить: для разрешения типов хватает и такого разбора
 */
final class GradleBuildFiles {
    private static final Set<String> BUILD_FILES = Set.of("build.gradle", "build.gradle.kts");
    private static final String PROPERTIES_FILE = "gradle.properties";
    private static final Path CATALOG = Path.of("gradle", "libs.versions.toml");
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of("build", "target", "out", "node_modules", ".git", ".gradle", ".idea", "buildSrc");

    // Конфигурации, которые попадают в код приложения
    private static final String CONFIGURATIONS =
            "(?:implementation|api|compile|compileOnly|compileOnlyApi|runtimeOnly|runtime|provided|providedCompile)";
    private static final Pattern STRING_NOTATION = Pattern.compile(
            "(?m)^\\s*" + CONFIGURATIONS + "\\s*\\(?\\s*(?:platform\\(|enforcedPlatform\\()?\\s*['\"]"
                    + "([^'\"\\s:]+):([^'\"\\s:]+)(?::([^'\"\\s:@]+))?[^'\"]*['\"]");
    private static final Pattern MAP_NOTATION = Pattern.compile(
            "(?m)^\\s*" + CONFIGURATIONS + "\\s*\\(?\\s*group\\s*[:=]\\s*['\"]([^'\"]+)['\"]\\s*,\\s*name\\s*[:=]\\s*"
                    + "['\"]([^'\"]+)['\"](?:\\s*,\\s*version\\s*[:=]\\s*['\"]([^'\"]+)['\"])?");
    private static final Pattern CATALOG_NOTATION = Pattern.compile(
            "(?m)^\\s*" + CONFIGURATIONS + "\\s*\\(?\\s*(?:platform\\(|enforcedPlatform\\()?\\s*libs\\.([\\w.]+)");
    private static final Pattern PLATFORM = Pattern.compile(
            "(?:platform|enforcedPlatform|mavenBom)\\s*\\(?\\s*['\"]([^'\"\\s:]+):([^'\"\\s:]+):([^'\"\\s:]+)['\"]");
    private static final Pattern SPRING_BOOT_PLUGIN = Pattern.compile(
            "id\\s*\\(?\\s*['\"]org\\.springframework\\.boot['\"]\\s*\\)?\\s*version\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
    // ext.springVersion = '6.1.0', set("springVersion", "6.1.0"), val springVersion = "6.1.0"
    private static final Pattern VARIABLE = Pattern.compile(
            "(?m)^\\s*(?:ext\\.|val\\s+|def\\s+|set\\(\\s*['\"])?(\\w[\\w.]*)['\"]?\\s*[=,]\\s*['\"]([^'\"$]+)['\"]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{?([\\w.]+)}?");
    private static final String SPRING_BOOT_GROUP = "org.springframework.boot";
    private static final String SPRING_BOOT_BOM = "spring-boot-dependencies";

    /**
     * @param group     группа
     * @param artifact  имя артефакта
     * @param version   версия либо пустая строка, если ее задает BOM
     */
    record Coordinate(String group, String artifact, String version) {
    }

    /**
     * @param dependencies зависимости кода приложения
     * @param platforms    BOM, которые задают версии
     */
    record Declared(List<Coordinate> dependencies, List<Coordinate> platforms) {

        boolean isEmpty() {
            return dependencies.isEmpty() && platforms.isEmpty();
        }
    }

    private GradleBuildFiles() {
    }

    /**
     * @param root папка проекта либо файл в ней
     * @return зависимости всех файлов сборки Gradle в папке; пусто, если это не проект Gradle
     */
    static Declared read(Path root) {
        Path directory = Files.isDirectory(root) ? root : root.toAbsolutePath().getParent();
        List<Path> buildFiles = directory == null ? List.of() : find(directory);
        if (buildFiles.isEmpty()) {
            return new Declared(List.of(), List.of());
        }
        Map<String, String> variables = new HashMap<>();
        Map<String, Coordinate> catalog = new HashMap<>();
        List<String> texts = new ArrayList<>();
        for (Path file : buildFiles) {
            Path module = file.getParent();
            readProperties(module.resolve(PROPERTIES_FILE), variables);
            readCatalog(module.resolve(CATALOG), catalog);
            readText(file).ifPresent(texts::add);
        }
        readProperties(directory.resolve(PROPERTIES_FILE), variables);
        readCatalog(directory.resolve(CATALOG), catalog);
        for (String text : texts) {
            Matcher variable = VARIABLE.matcher(text);
            while (variable.find()) {
                variables.putIfAbsent(variable.group(1), variable.group(2));
            }
        }

        List<Coordinate> dependencies = new ArrayList<>();
        List<Coordinate> platforms = new ArrayList<>();
        for (String text : texts) {
            collect(STRING_NOTATION.matcher(text), variables, dependencies);
            collect(MAP_NOTATION.matcher(text), variables, dependencies);
            Matcher alias = CATALOG_NOTATION.matcher(text);
            while (alias.find()) {
                Optional.ofNullable(catalog.get(normalize(alias.group(1)))).ifPresent(dependencies::add);
            }
            collect(PLATFORM.matcher(text), variables, platforms);
            Matcher boot = SPRING_BOOT_PLUGIN.matcher(text);
            while (boot.find()) {
                platforms.add(new Coordinate(SPRING_BOOT_GROUP, SPRING_BOOT_BOM, substitute(boot.group(1), variables)));
            }
        }
        return new Declared(dependencies, platforms);
    }

    private static void collect(Matcher matcher, Map<String, String> variables, List<Coordinate> into) {
        while (matcher.find()) {
            String version = matcher.group(3) == null ? "" : substitute(matcher.group(3), variables);
            into.add(new Coordinate(matcher.group(1), matcher.group(2), version));
        }
    }

    // $springVersion и ${springVersion} из gradle.properties и ext; что не нашлось, остается как есть
    private static String substitute(String value, Map<String, String> variables) {
        Matcher placeholder = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (placeholder.find()) {
            String replacement = variables.get(placeholder.group(1));
            placeholder.appendReplacement(result, Matcher.quoteReplacement(
                    replacement == null ? placeholder.group() : replacement));
        }
        placeholder.appendTail(result);
        return result.toString();
    }

    private static void readProperties(Path file, Map<String, String> variables) {
        readText(file).ifPresent(text -> text.lines()
                .map(String::trim)
                .filter(line -> !line.startsWith("#") && line.contains("="))
                .forEach(line -> variables.putIfAbsent(
                        line.substring(0, line.indexOf('=')).trim(), line.substring(line.indexOf('=') + 1).trim())));
    }

    /**
     * [versions] spring = "6.1.0"; [libraries] spring-core = { module = "org.springframework:spring-core",
     * version.ref = "spring" }, а также "g:n:v" и { group = "g", name = "n", version = "v" }
     */
    private static void readCatalog(Path file, Map<String, Coordinate> catalog) {
        Optional<String> text = readText(file);
        if (text.isEmpty()) {
            return;
        }
        Map<String, String> versions = new HashMap<>();
        String section = "";
        List<String[]> libraries = new ArrayList<>();
        for (String raw : text.get().lines().toList()) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.startsWith("[")) {
                section = line;
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if ("[versions]".equals(section)) {
                versions.put(key, unquote(value));
            } else if ("[libraries]".equals(section)) {
                libraries.add(new String[] {key, value});
            }
        }
        for (String[] library : libraries) {
            parseLibrary(library[1], versions).ifPresent(found -> catalog.put(normalize(library[0]), found));
        }
    }

    private static Optional<Coordinate> parseLibrary(String value, Map<String, String> versions) {
        if (value.startsWith("\"")) {
            String[] parts = unquote(value).split(":");
            return parts.length >= 2
                    ? Optional.of(new Coordinate(parts[0], parts[1], parts.length > 2 ? parts[2] : ""))
                    : Optional.empty();
        }
        String module = field(value, "module");
        String group = module.contains(":") ? module.substring(0, module.indexOf(':')) : field(value, "group");
        String name = module.contains(":") ? module.substring(module.indexOf(':') + 1) : field(value, "name");
        String version = field(value, "version");
        String reference = field(value, "version.ref");
        if (!reference.isEmpty()) {
            version = versions.getOrDefault(reference, "");
        }
        return group.isEmpty() || name.isEmpty() ? Optional.empty() : Optional.of(new Coordinate(group, name, version));
    }

    // module = "g:n" внутри { ... }
    private static String field(String value, String name) {
        Matcher matcher = Pattern.compile("(?:^|[{,\\s])" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String unquote(String value) {
        return value.replaceAll("^\"|\"$", "");
    }

    // spring-boot-starter и spring_boot_starter в каталоге - это libs.spring.boot.starter в build.gradle
    private static String normalize(String alias) {
        return alias.replaceAll("[-_]", ".").toLowerCase();
    }

    private static Optional<String> readText(Path file) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static List<Path> find(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(path -> path.getFileName() != null && BUILD_FILES.contains(path.getFileName().toString()))
                    .filter(path -> {
                        for (Path part : directory.relativize(path)) {
                            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .sorted()
                    .toList();
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }
}
