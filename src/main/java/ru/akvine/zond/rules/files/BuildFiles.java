package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.parsers.XmlElement;
import ru.akvine.zond.parsers.XmlParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Зависимости из pom.xml и build.gradle. pom.xml разбирается как XML, версии вида ${...} подставляются
 * из properties самого файла и родительских pom.xml. build.gradle - программа на Groovy или Kotlin,
 * поэтому из него берутся только строки вида implementation 'group:artifact:version'.
 */
@UtilityClass
public class BuildFiles {
    private static final String TEST_SCOPE = "test";
    private static final String DEFAULT_SCOPE = "compile";

    private static final String DEPENDENCY = "dependency";
    private static final String DEPENDENCIES = "dependencies";
    private static final String MANAGEMENT = "dependencyManagement";
    private static final String PLUGIN = "plugin";
    private static final String PARENT = "parent";
    private static final String PROPERTIES = "properties";
    private static final String GROUP_ID = "groupId";
    private static final String ARTIFACT_ID = "artifactId";
    private static final String VERSION = "version";
    private static final String SCOPE = "scope";
    private static final String RELATIVE_PATH = "relativePath";
    private static final String DEFAULT_PARENT_PATH = "../pom.xml";
    private static final String POM_NAME = "pom.xml";

    // ${revision}, ${project.version}
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Set<String> PROJECT_VERSION_KEYS = Set.of("project.version", "pom.version", "version");
    private static final String PARENT_VERSION_KEY = "project.parent.version";

    // Родитель родителя и так далее: цепочка, замкнутая сама на себя, иначе не закончилась бы
    private static final int MAX_PARENTS = 10;

    // implementation 'group:artifact:1.0', testImplementation("group:artifact"), api platform("group:bom:1.0")
    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
            "^\\s*(\\w+)\\s*\\(?\\s*(?:(?:enforcedPlatform|platform)\\s*\\(\\s*)?['\"]([^:'\"\\s]+):([^:'\"\\s]+)(?::([^'\"@\\s]+))?");
    private static final Set<String> GRADLE_CONFIGURATIONS = Set.of(
            "implementation", "api", "compileOnly", "compileOnlyApi", "runtimeOnly", "annotationProcessor",
            "developmentOnly", "compile", "runtime", "kapt", "testImplementation", "testCompileOnly",
            "testRuntimeOnly", "testAnnotationProcessor", "testCompile", "testRuntime", "testFixturesImplementation",
            "testFixturesApi", "integrationTestImplementation");

    /**
     * @param scope   область в Maven (compile, test, provided) либо конфигурация в Gradle (implementation)
     * @param version версия либо пустая строка, если она задается не здесь; ${...} уже заменено значением,
     *                если его удалось найти
     * @param managed объявлена в dependencyManagement: это не сама зависимость, а только ее версия
     */
    public record Dependency(String group, String artifact, String version, String scope, int line, boolean managed) {

        public String coordinates() {
            return group + ":" + artifact;
        }

        public boolean isTestOnly() {
            return TEST_SCOPE.equals(scope) || scope.startsWith(TEST_SCOPE) || scope.startsWith("integrationTest");
        }
    }

    /**
     * @param allFiles все файлы проекта: среди них ищется родительский pom.xml
     */
    public List<Dependency> dependencies(TextFile file, List<TextFile> allFiles) {
        if (TextFiles.isPom(file)) {
            return pomDependencies(file, allFiles);
        }
        return TextFiles.isGradle(file) ? gradleDependencies(file) : List.of();
    }

    private List<Dependency> pomDependencies(TextFile file, List<TextFile> allFiles) {
        Optional<XmlElement> project = XmlParser.parse(file.lines());
        if (project.isEmpty()) {
            return List.of();
        }
        Map<String, String> properties = propertiesOf(file, project.get(), allFiles);
        List<Dependency> dependencies = new ArrayList<>();
        collect(project.get(), false, properties, dependencies);
        return dependencies;
    }

    // Зависимости плагинов сборки в приложение не попадают; исключения (exclusions) зависимостями не являются
    private void collect(XmlElement element, boolean managed, Map<String, String> properties, List<Dependency> found) {
        for (XmlElement child : element.children()) {
            if (child.name().equals(PLUGIN)) {
                continue;
            }
            if (child.name().equals(DEPENDENCIES)) {
                for (XmlElement dependency : child.children(DEPENDENCY)) {
                    String scope = dependency.childText(SCOPE);
                    found.add(new Dependency(
                            resolve(dependency.childText(GROUP_ID), properties),
                            resolve(dependency.childText(ARTIFACT_ID), properties),
                            resolve(dependency.childText(VERSION), properties),
                            scope.isEmpty() ? DEFAULT_SCOPE : scope,
                            dependency.line(),
                            managed));
                }
            } else {
                collect(child, managed || child.name().equals(MANAGEMENT), properties, found);
            }
        }
    }

    // Свойства самого файла важнее свойств родителя, поэтому родители читаются первыми
    private Map<String, String> propertiesOf(TextFile file, XmlElement project, List<TextFile> allFiles) {
        List<XmlElement> chain = new ArrayList<>(List.of(project));
        TextFile current = file;
        XmlElement currentProject = project;
        while (chain.size() <= MAX_PARENTS) {
            Optional<TextFile> parentFile = parentOf(current, currentProject, allFiles);
            Optional<XmlElement> parentProject = parentFile.flatMap(parent -> XmlParser.parse(parent.lines()));
            if (parentProject.isEmpty()) {
                break;
            }
            chain.add(parentProject.get());
            current = parentFile.get();
            currentProject = parentProject.get();
        }

        Map<String, String> properties = new HashMap<>();
        for (int index = chain.size() - 1; index >= 0; index--) {
            chain.get(index).child(PROPERTIES).ifPresent(block ->
                    block.children().forEach(property -> properties.put(property.name(), property.text())));
        }

        // Версия модуля, если своя не задана, наследуется от родителя
        String parentVersion = project.child(PARENT).map(parent -> parent.childText(VERSION)).orElse("");
        String ownVersion = project.childText(VERSION);
        String version = ownVersion.isEmpty() ? parentVersion : ownVersion;
        PROJECT_VERSION_KEYS.forEach(key -> properties.put(key, version));
        properties.put(PARENT_VERSION_KEY, parentVersion);
        return properties;
    }

    // <relativePath> по умолчанию - ../pom.xml; путь к каталогу означает pom.xml в нем
    private Optional<TextFile> parentOf(TextFile file, XmlElement project, List<TextFile> allFiles) {
        Optional<XmlElement> parent = project.child(PARENT);
        Path directory = file.path().toAbsolutePath().getParent();
        if (parent.isEmpty() || directory == null) {
            return Optional.empty();
        }
        String relativePath = parent.get().child(RELATIVE_PATH).map(XmlElement::text).orElse(DEFAULT_PARENT_PATH);
        if (relativePath.isBlank()) {
            // Пустой <relativePath/> - родителя берут из репозитория, в проекте его нет
            return Optional.empty();
        }
        Path target = directory.resolve(relativePath).normalize();
        Path expected = target.getFileName() != null && target.getFileName().toString().endsWith(".xml")
                ? target
                : target.resolve(POM_NAME);
        return allFiles.stream()
                .filter(TextFiles::isPom)
                .filter(candidate -> candidate.path().toAbsolutePath().normalize().equals(expected))
                .findFirst();
    }

    // Значение свойства само может ссылаться на другое свойство - подставляем несколько раз
    private String resolve(String value, Map<String, String> properties) {
        String resolved = value;
        for (int attempt = 0; attempt < MAX_PARENTS && resolved.contains("${"); attempt++) {
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

    private List<Dependency> gradleDependencies(TextFile file) {
        List<Dependency> dependencies = new ArrayList<>();
        for (int index = 0; index < file.lines().size(); index++) {
            Matcher matcher = GRADLE_DEPENDENCY.matcher(file.lines().get(index));
            if (matcher.find() && GRADLE_CONFIGURATIONS.contains(matcher.group(1))) {
                String version = Optional.ofNullable(matcher.group(4)).orElse("");
                dependencies.add(new Dependency(
                        matcher.group(2), matcher.group(3), version, matcher.group(1), index + 1, false));
            }
        }
        return dependencies;
    }
}
