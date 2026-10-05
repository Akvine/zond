package ru.akvine.zond.loaders;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.parsers.XmlElement;
import ru.akvine.zond.parsers.XmlParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Находит библиотеки проверяемого проекта без запуска Maven: читает его pom.xml, а jar-файлы и описания
 * зависимостей берет из локального репозитория (~/.m2/repository).
 * <p>
 * Версия зависимости берется оттуда же, откуда ее взял бы Maven: из самой зависимости, из
 * dependencyManagement проекта, его родительских pom.xml и подключенных BOM (так задает версии Spring Boot).
 * Зависимости зависимостей читаются из их pom-файлов в репозитории. Это упрощенное повторение работы Maven:
 * исключения (exclusions), профили и диапазоны версий не учитываются. Для разрешения типов этого достаточно -
 * лишняя или чуть другая версия библиотеки на находки почти не влияет.
 */
@Component
public class MavenClasspathResolver {
    private static final String POM_FILE = "pom.xml";
    private static final String DEFAULT_PARENT_PATH = "../pom.xml";
    private static final String SETTINGS_FILE = "settings.xml";
    private static final String LOCAL_REPOSITORY = "localRepository";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    // Каталоги сборки и служебные: там лежат копии pom.xml либо чужие проекты
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of("build", "target", "out", "node_modules", ".git", ".gradle", ".idea");

    // Зависимости зависимостей с такой областью в приложение не попадают
    private static final Set<String> NOT_TRANSITIVE_SCOPES = Set.of("test", "provided", "system");
    private static final String SYSTEM_SCOPE = "system";
    private static final String IMPORT_SCOPE = "import";
    private static final String POM_TYPE = "pom";
    private static final String JAR_TYPE = "jar";

    // Защита от pom-файлов, ссылающихся друг на друга, и от слишком длинных цепочек
    private static final int MAX_PARENTS = 20;
    private static final int MAX_DEPTH = 15;
    private static final int MAX_SUBSTITUTIONS = 10;

    /**
     * @param jars       найденные jar-файлы
     * @param missing    зависимости, которых в локальном репозитории нет: проект еще не собирали либо
     *                   библиотека лежит в недоступном репозитории
     * @param repository где искали
     */
    public record Resolved(List<Path> jars, List<String> missing, Path repository) {
    }

    private record Dependency(String group, String artifact, String version, String scope, String type, boolean optional) {

        String key() {
            return group + ":" + artifact;
        }
    }

    /**
     * pom.xml после наследования: со свойствами и версиями от родителей и подключенных BOM
     *
     * @param managed версии из dependencyManagement: "группа:артефакт" -> версия
     */
    private record Model(
            String group, String artifact, String version, Map<String, String> properties,
            Map<String, String> managed, List<Dependency> dependencies) {
    }

    /**
     * Зависимость в очереди на разбор
     *
     * @param managed версии, действующие в pom-файле, где зависимость объявлена
     */
    private record Pending(Dependency dependency, Map<String, String> managed, int depth, boolean direct) {
    }

    private final Path repository;
    private final Map<Path, Optional<Model>> models = new HashMap<>();

    @Autowired
    public MavenClasspathResolver(ZondSettings settings) {
        this(settings.mavenRepository().orElseGet(MavenClasspathResolver::defaultRepository));
    }

    public MavenClasspathResolver(Path repository) {
        this.repository = repository;
    }

    /**
     * @param root что сканируется: папка проекта либо файл в ней
     * @return библиотеки всех pom.xml, найденных в папке; пусто, если это не проект Maven
     */
    public synchronized Resolved resolve(Path root) {
        models.clear();
        List<Model> projects = new ArrayList<>();
        for (Path pom : findPoms(root)) {
            model(pom, 0).ifPresent(projects::add);
        }

        // Версия, заданная проектом, важнее той, что указана в самой библиотеке
        Map<String, String> projectManaged = new LinkedHashMap<>();
        Set<String> own = new HashSet<>();
        Deque<Pending> queue = new ArrayDeque<>();
        for (Model project : projects) {
            project.managed().forEach(projectManaged::putIfAbsent);
            own.add(project.group() + ":" + project.artifact());
            project.dependencies().forEach(dependency -> queue.add(new Pending(dependency, project.managed(), 0, true)));
        }

        Set<Path> jars = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        Set<String> seen = new HashSet<>(own);
        while (!queue.isEmpty()) {
            Pending pending = queue.poll();
            Dependency dependency = pending.dependency();
            boolean isSkipped = SYSTEM_SCOPE.equals(dependency.scope())
                    || !pending.direct() && (dependency.optional() || NOT_TRANSITIVE_SCOPES.contains(dependency.scope()));
            if (isSkipped || dependency.group().contains("$") || !seen.add(dependency.key())) {
                continue;
            }

            Optional<String> version = versionOf(dependency, projectManaged, pending.managed());
            Path directory = version.map(found -> artifactDirectory(dependency).resolve(found)).orElse(null);
            if (directory == null || !Files.isDirectory(directory)) {
                // Отсутствие чужой необязательной мелочи не интересно; о своих зависимостях стоит сказать
                if (pending.direct()) {
                    missing.add(dependency.key() + version.map(found -> ":" + found).orElse(""));
                }
                continue;
            }
            String fileName = dependency.artifact() + "-" + version.get();
            Path jar = directory.resolve(fileName + ".jar");
            if (JAR_TYPE.equals(dependency.type()) && Files.isRegularFile(jar)) {
                jars.add(jar);
            }
            if (pending.depth() < MAX_DEPTH) {
                model(directory.resolve(fileName + ".pom"), 0).ifPresent(model -> model.dependencies().forEach(
                        next -> queue.add(new Pending(next, model.managed(), pending.depth() + 1, false))));
            }
        }
        return new Resolved(jars.stream().sorted().toList(), missing, repository);
    }

    // ------------------------------------------------------------------------------------------- версии

    private Optional<String> versionOf(
            Dependency dependency, Map<String, String> projectManaged, Map<String, String> ownerManaged) {
        String managed = projectManaged.get(dependency.key());
        if (managed != null && isResolved(managed)) {
            return Optional.of(managed);
        }
        if (isResolved(dependency.version())) {
            return Optional.of(dependency.version());
        }
        String byOwner = ownerManaged.get(dependency.key());
        if (byOwner != null && isResolved(byOwner)) {
            return Optional.of(byOwner);
        }
        // Версию узнать не удалось: берем самую свежую из тех, что уже скачаны
        return latestInstalled(dependency);
    }

    // Версия задана и это не диапазон [1.0,2.0) и не неподставленное свойство
    private boolean isResolved(String version) {
        return !version.isEmpty() && !version.contains("$") && !version.contains("[") && !version.contains("(")
                && !version.contains(",");
    }

    private Optional<String> latestInstalled(Dependency dependency) {
        Path directory = artifactDirectory(dependency);
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (Stream<Path> versions = Files.list(directory)) {
            return versions.filter(Files::isDirectory)
                    .map(version -> version.getFileName().toString())
                    .max(Comparator.comparing(this::versionKey));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    // 3.10.2 должно быть больше 3.9.9: числа в версии дополняются нулями слева и сравниваются как строки
    private String versionKey(String version) {
        Matcher number = NUMBER.matcher(version);
        StringBuilder key = new StringBuilder();
        while (number.find()) {
            key.append(String.format("%010d", Long.parseLong(number.group().substring(0, Math.min(9, number.group().length())))));
        }
        // 1.0 новее, чем 1.0-SNAPSHOT и 1.0-RC1
        return key + (version.matches("[\\d.]+(\\.RELEASE|\\.Final)?") ? "~" : "") + version;
    }

    private Path artifactDirectory(Dependency dependency) {
        Path directory = repository;
        for (String part : dependency.group().split("\\.")) {
            directory = directory.resolve(part);
        }
        return directory.resolve(dependency.artifact());
    }

    // ------------------------------------------------------------------------------------------ pom.xml

    private Optional<Model> model(Path pom, int level) {
        Path file = pom.toAbsolutePath().normalize();
        Optional<Model> known = models.get(file);
        if (known != null) {
            return known;
        }
        // Пока pom разбирается, для ссылающихся на него он пуст: иначе два pom, указывающих друг на друга,
        // разбирались бы бесконечно
        models.put(file, Optional.empty());
        Optional<Model> model = level > MAX_PARENTS ? Optional.empty() : read(file, level);
        models.put(file, model);
        return model;
    }

    private Optional<Model> read(Path file, int level) {
        Optional<XmlElement> root;
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            root = XmlParser.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
        if (root.isEmpty()) {
            return Optional.empty();
        }
        XmlElement project = root.get();

        Optional<XmlElement> parentElement = project.child("parent");
        Optional<Model> parent = parentElement.flatMap(element -> parentPom(file, element))
                .flatMap(parentFile -> model(parentFile, level + 1));

        String parentGroup = parentElement.map(element -> element.childText("groupId")).orElse("");
        String parentVersion = parentElement.map(element -> element.childText("version")).orElse("");
        String group = project.childText("groupId").isEmpty() ? parentGroup : project.childText("groupId");
        String version = project.childText("version").isEmpty() ? parentVersion : project.childText("version");
        String artifact = project.childText("artifactId");

        Map<String, String> properties = new HashMap<>(parent.map(Model::properties).orElse(Map.of()));
        project.child("properties").ifPresent(block ->
                block.children().forEach(property -> properties.put(property.name(), property.text())));
        for (String prefix : List.of("project.", "pom.", "")) {
            properties.put(prefix + "version", version);
            properties.put(prefix + "groupId", group);
            properties.put(prefix + "artifactId", artifact);
        }
        properties.put("project.parent.version", parentVersion);
        properties.put("project.parent.groupId", parentGroup);

        // Порядок важности: свои записи, затем подключенные BOM, затем родитель
        Map<String, String> managed = new LinkedHashMap<>();
        List<Dependency> imports = new ArrayList<>();
        for (Dependency entry : dependenciesOf(project.child("dependencyManagement").orElse(null), properties)) {
            if (POM_TYPE.equals(entry.type()) && IMPORT_SCOPE.equals(entry.scope())) {
                imports.add(entry);
            } else {
                managed.putIfAbsent(entry.key(), entry.version());
            }
        }
        for (Dependency bom : imports) {
            if (isResolved(bom.version())) {
                Path bomFile = artifactDirectory(bom).resolve(bom.version())
                        .resolve(bom.artifact() + "-" + bom.version() + ".pom");
                model(bomFile, level + 1).ifPresent(imported -> imported.managed().forEach(managed::putIfAbsent));
            }
        }
        parent.ifPresent(inherited -> inherited.managed().forEach(managed::putIfAbsent));

        List<Dependency> dependencies = new ArrayList<>(dependenciesOf(project, properties));
        parent.ifPresent(inherited -> dependencies.addAll(inherited.dependencies()));
        return Optional.of(new Model(group, artifact, version, properties, managed, dependencies));
    }

    // Родитель лежит рядом (многомодульный проект) либо в репозитории
    private Optional<Path> parentPom(Path file, XmlElement parent) {
        String relativePath = parent.child("relativePath").map(XmlElement::text).orElse(DEFAULT_PARENT_PATH);
        Path directory = file.getParent();
        if (!relativePath.isBlank() && directory != null) {
            try {
                Path local = directory.resolve(relativePath).normalize();
                Path localPom = Files.isDirectory(local) ? local.resolve(POM_FILE) : local;
                if (Files.isRegularFile(localPom)) {
                    return Optional.of(localPom);
                }
            } catch (InvalidPathException exception) {
                // В relativePath бывает записано не то, что путь: <relativePath>org.apache:apache</relativePath>
            }
        }
        Dependency coordinates = new Dependency(
                parent.childText("groupId"), parent.childText("artifactId"), parent.childText("version"), "", POM_TYPE, false);
        if (!isResolved(coordinates.version())) {
            return Optional.empty();
        }
        return Optional.of(artifactDirectory(coordinates).resolve(coordinates.version())
                .resolve(coordinates.artifact() + "-" + coordinates.version() + ".pom"));
    }

    /**
     * @param owner элемент, внутри которого лежит блок dependencies: project либо dependencyManagement
     */
    private List<Dependency> dependenciesOf(XmlElement owner, Map<String, String> properties) {
        List<Dependency> dependencies = new ArrayList<>();
        if (owner == null) {
            return dependencies;
        }
        for (XmlElement block : owner.children("dependencies")) {
            for (XmlElement dependency : block.children("dependency")) {
                String type = substitute(dependency.childText("type"), properties);
                dependencies.add(new Dependency(
                        substitute(dependency.childText("groupId"), properties),
                        substitute(dependency.childText("artifactId"), properties),
                        substitute(dependency.childText("version"), properties),
                        substitute(dependency.childText("scope"), properties),
                        type.isEmpty() ? JAR_TYPE : type,
                        Boolean.parseBoolean(dependency.childText("optional"))));
            }
        }
        return dependencies;
    }

    // Значение свойства само может ссылаться на другое свойство
    private String substitute(String value, Map<String, String> properties) {
        String resolved = value;
        for (int attempt = 0; attempt < MAX_SUBSTITUTIONS && resolved.contains("${"); attempt++) {
            Matcher placeholder = PLACEHOLDER.matcher(resolved);
            StringBuilder result = new StringBuilder();
            boolean changed = false;
            while (placeholder.find()) {
                String replacement = properties.get(placeholder.group(1));
                changed |= replacement != null;
                placeholder.appendReplacement(result,
                        Matcher.quoteReplacement(replacement == null ? placeholder.group() : replacement));
            }
            placeholder.appendTail(result);
            resolved = result.toString();
            if (!changed) {
                break;
            }
        }
        return resolved;
    }

    private List<Path> findPoms(Path root) {
        Path directory = Files.isDirectory(root) ? root : root.toAbsolutePath().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(path -> path.getFileName() != null && POM_FILE.equals(path.getFileName().toString()))
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

    // ~/.m2/repository, если в ~/.m2/settings.xml не указан другой каталог
    private static Path defaultRepository() {
        Path home = Path.of(System.getProperty("user.home", "."), ".m2");
        Path settings = home.resolve(SETTINGS_FILE);
        try {
            if (Files.isRegularFile(settings)) {
                Optional<String> configured = XmlParser.parse(Files.readAllLines(settings, StandardCharsets.UTF_8))
                        .map(root -> root.childText(LOCAL_REPOSITORY))
                        .filter(path -> !path.isBlank() && !path.contains("$"));
                if (configured.isPresent()) {
                    return Path.of(configured.get());
                }
            }
        } catch (IOException | RuntimeException exception) {
            // Нечитаемые настройки Maven - не повод отказываться от каталога по умолчанию
        }
        return home.resolve("repository");
    }
}
