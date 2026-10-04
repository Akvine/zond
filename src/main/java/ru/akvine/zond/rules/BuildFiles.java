package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Зависимости из pom.xml и build.gradle. Файлы читаются как текст: полный разбор XML и Groovy не нужен,
 * достаточно блоков dependency и строк вида implementation 'group:artifact:version'.
 */
@UtilityClass
class BuildFiles {
    private static final String TEST_SCOPE = "test";

    private static final Pattern POM_FIELD = Pattern.compile("<(groupId|artifactId|version|scope)>\\s*([^<]*?)\\s*</\\1>");
    private static final String DEPENDENCY_START = "<dependency>";
    private static final String DEPENDENCY_END = "</dependency>";
    private static final String MANAGEMENT_START = "<dependencyManagement>";
    private static final String MANAGEMENT_END = "</dependencyManagement>";
    private static final String EXCLUSIONS_START = "<exclusions>";
    private static final String EXCLUSIONS_END = "</exclusions>";
    private static final String COMMENT_START = "<!--";
    private static final String COMMENT_END = "-->";

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
     * @param version версия либо пустая строка, если она задается не здесь
     * @param managed объявлена в dependencyManagement: это не сама зависимость, а только ее версия
     */
    record Dependency(String group, String artifact, String version, String scope, int line, boolean managed) {

        String coordinates() {
            return group + ":" + artifact;
        }

        boolean isTestOnly() {
            return TEST_SCOPE.equals(scope) || scope.startsWith(TEST_SCOPE) || scope.startsWith("integrationTest");
        }
    }

    List<Dependency> dependencies(TextFile file) {
        if (TextFiles.isPom(file)) {
            return pomDependencies(file);
        }
        return TextFiles.isGradle(file) ? gradleDependencies(file) : List.of();
    }

    private List<Dependency> pomDependencies(TextFile file) {
        List<Dependency> dependencies = new ArrayList<>();
        boolean inComment = false;
        boolean inManagement = false;
        boolean inExclusions = false;
        // Поля зависимости, блок которой сейчас читается: groupId, artifactId, version, scope
        String[] fields = null;
        int startLine = 0;

        for (int index = 0; index < file.lines().size(); index++) {
            String line = file.lines().get(index);
            if (inComment) {
                inComment = !line.contains(COMMENT_END);
                continue;
            }
            if (line.contains(COMMENT_START) && !line.contains(COMMENT_END)) {
                inComment = true;
                continue;
            }
            if (line.contains(COMMENT_START)) {
                continue;
            }

            inManagement = line.contains(MANAGEMENT_START) || inManagement && !line.contains(MANAGEMENT_END);
            // Исключения перечисляются теми же тегами groupId и artifactId - к самой зависимости они не относятся
            inExclusions = line.contains(EXCLUSIONS_START) || inExclusions && !line.contains(EXCLUSIONS_END);
            if (line.contains(DEPENDENCY_START)) {
                fields = new String[] {"", "", "", "compile"};
                startLine = index + 1;
            }
            if (fields != null && !inExclusions) {
                Matcher field = POM_FIELD.matcher(line);
                while (field.find()) {
                    fields[fieldIndex(field.group(1))] = field.group(2);
                }
            }
            if (fields != null && line.contains(DEPENDENCY_END)) {
                dependencies.add(new Dependency(fields[0], fields[1], fields[2], fields[3], startLine, inManagement));
                fields = null;
            }
        }
        return dependencies;
    }

    private int fieldIndex(String tag) {
        return switch (tag) {
            case "groupId" -> 0;
            case "artifactId" -> 1;
            case "version" -> 2;
            default -> 3;
        };
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
