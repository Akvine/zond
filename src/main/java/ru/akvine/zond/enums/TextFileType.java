package ru.akvine.zond.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Что за файл перед нами. Часть файлов узнается по имени (pom.xml, Dockerfile), часть - только по содержимому:
 * журнал Liquibase и манифест Kubernetes могут называться как угодно.
 */
@AllArgsConstructor
@Getter
public enum TextFileType {
    SQL(FileKind.SQL),
    LIQUIBASE_XML(FileKind.SQL),
    LIQUIBASE_YAML(FileKind.SQL),
    POM(FileKind.BUILD_FILES),
    GRADLE(FileKind.BUILD_FILES),
    DOCKERFILE(FileKind.DOCKER),
    COMPOSE(FileKind.DOCKER),
    MESSAGES(FileKind.MESSAGES),
    LOGBACK(FileKind.LOGGING),
    LOG4J2(FileKind.LOGGING),
    KUBERNETES(FileKind.KUBERNETES),
    GITLAB_CI(FileKind.CI),
    GITHUB_WORKFLOW(FileKind.CI);

    private static final Pattern SQL_NAME = Pattern.compile(".*\\.sql");
    private static final Pattern XML_NAME = Pattern.compile(".*\\.xml");
    private static final Pattern YAML_NAME = Pattern.compile(".*\\.ya?ml");
    private static final Pattern GRADLE_NAME = Pattern.compile("build\\.gradle(\\.kts)?");
    private static final Pattern DOCKERFILE_NAME = Pattern.compile("dockerfile(\\..*)?|.*\\.dockerfile");
    private static final Pattern MESSAGES_NAME = Pattern.compile("messages.*\\.properties");
    private static final Pattern LOGBACK_NAME = Pattern.compile("logback.*\\.xml");
    private static final Pattern LOG4J2_NAME = Pattern.compile("log4j2.*\\.xml");
    private static final Pattern COMPOSE_NAME = Pattern.compile("(docker-)?compose.*\\.ya?ml");
    private static final Pattern GITLAB_CI_NAME = Pattern.compile("\\.gitlab-ci.*\\.ya?ml");

    // Настройки Spring читает и разбирает ConfigLoader
    private static final Pattern SPRING_CONFIG_NAME = Pattern.compile("(application|bootstrap).*\\.ya?ml");
    private static final String POM_NAME = "pom.xml";
    private static final String GITHUB_DIRECTORY = ".github";
    private static final String WORKFLOWS_DIRECTORY = "workflows";

    private static final String LIQUIBASE_XML_ROOT = "<databaseChangeLog";
    private static final Pattern LIQUIBASE_YAML_ROOT = Pattern.compile("^databaseChangeLog\\s*:.*");
    private static final Pattern KUBERNETES_API_VERSION = Pattern.compile("^apiVersion\\s*:.*");
    private static final Pattern KUBERNETES_KIND = Pattern.compile("^kind\\s*:.*");

    /**
     * Настройка, которой проверка таких файлов отключается
     */
    private final FileKind kind;

    /**
     * @return true, если файл с таким именем может оказаться проверяемым и его стоит прочитать
     */
    public static boolean isCandidate(Path path) {
        String name = lowerName(path);
        return SQL_NAME.matcher(name).matches()
                || XML_NAME.matcher(name).matches()
                || YAML_NAME.matcher(name).matches() && !SPRING_CONFIG_NAME.matcher(name).matches()
                || GRADLE_NAME.matcher(name).matches()
                || DOCKERFILE_NAME.matcher(name).matches()
                || MESSAGES_NAME.matcher(name).matches();
    }

    /**
     * @return вид файла либо пусто, если проверять в нем нечего
     */
    public static Optional<TextFileType> of(Path path, List<String> lines) {
        String name = lowerName(path);
        if (XML_NAME.matcher(name).matches()) {
            return ofXml(name, lines);
        }
        if (YAML_NAME.matcher(name).matches()) {
            return SPRING_CONFIG_NAME.matcher(name).matches() ? Optional.empty() : ofYaml(path, name, lines);
        }
        if (SQL_NAME.matcher(name).matches()) {
            return Optional.of(SQL);
        }
        if (GRADLE_NAME.matcher(name).matches()) {
            return Optional.of(GRADLE);
        }
        if (DOCKERFILE_NAME.matcher(name).matches()) {
            return Optional.of(DOCKERFILE);
        }
        return MESSAGES_NAME.matcher(name).matches() ? Optional.of(MESSAGES) : Optional.empty();
    }

    private static Optional<TextFileType> ofXml(String name, List<String> lines) {
        if (POM_NAME.equals(name)) {
            return Optional.of(POM);
        }
        if (LOGBACK_NAME.matcher(name).matches()) {
            return Optional.of(LOGBACK);
        }
        if (LOG4J2_NAME.matcher(name).matches()) {
            return Optional.of(LOG4J2);
        }
        return lines.stream().anyMatch(line -> line.contains(LIQUIBASE_XML_ROOT))
                ? Optional.of(LIQUIBASE_XML)
                : Optional.empty();
    }

    private static Optional<TextFileType> ofYaml(Path path, String name, List<String> lines) {
        if (COMPOSE_NAME.matcher(name).matches()) {
            return Optional.of(COMPOSE);
        }
        if (GITLAB_CI_NAME.matcher(name).matches()) {
            return Optional.of(GITLAB_CI);
        }
        if (isGithubWorkflow(path)) {
            return Optional.of(GITHUB_WORKFLOW);
        }
        if (lines.stream().anyMatch(line -> LIQUIBASE_YAML_ROOT.matcher(line).matches())) {
            return Optional.of(LIQUIBASE_YAML);
        }
        boolean isManifest = lines.stream().anyMatch(line -> KUBERNETES_API_VERSION.matcher(line).matches())
                && lines.stream().anyMatch(line -> KUBERNETES_KIND.matcher(line).matches());
        return isManifest ? Optional.of(KUBERNETES) : Optional.empty();
    }

    // .github/workflows/build.yml
    private static boolean isGithubWorkflow(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null || parent.getParent() == null || parent.getFileName() == null) {
            return false;
        }
        Path grandParent = parent.getParent().getFileName();
        return WORKFLOWS_DIRECTORY.equals(parent.getFileName().toString())
                && grandParent != null && GITHUB_DIRECTORY.equals(grandParent.toString());
    }

    private static String lowerName(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }
}
