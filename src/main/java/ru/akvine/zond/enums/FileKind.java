package ru.akvine.zond.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Виды файлов, которые проверяются помимо Java-кода. Каждый вид можно отключить своей настройкой:
 * zond.scan.sql=false либо --scan-sql=false
 */
@Getter
public enum FileKind {
    SQL("sql", "SQL-файлы (*.sql)", ".*\\.sql"),
    BUILD_FILES("build-files", "Файлы сборки (pom.xml, build.gradle)", "pom\\.xml|build\\.gradle(\\.kts)?"),
    DOCKER("docker", "Dockerfile", "dockerfile(\\..*)?|.*\\.dockerfile"),
    CONFIG("config", "Настройки Spring (application*.properties, *.yml)",
            "(application|bootstrap).*\\.(properties|yml|yaml)"),
    MESSAGES("messages", "Файлы сообщений (messages*.properties)", "messages.*\\.properties");

    private static final String PROPERTY_PREFIX = "zond.scan.";
    private static final String OPTION_PREFIX = "scan-";

    private final String key;
    private final String description;
    private final Pattern fileName;

    FileKind(String key, String description, String fileName) {
        this.key = key;
        this.description = description;
        this.fileName = Pattern.compile(fileName, Pattern.CASE_INSENSITIVE);
    }

    /**
     * @return имя свойства в app.properties: zond.scan.sql
     */
    public String property() {
        return PROPERTY_PREFIX + key;
    }

    /**
     * @return имя аргумента командной строки без "--": scan-sql
     */
    public String option() {
        return OPTION_PREFIX + key;
    }

    /**
     * @return вид файла по его имени; у Java-файлов и прочих вида нет
     */
    public static Optional<FileKind> of(String name) {
        return Arrays.stream(values()).filter(kind -> kind.fileName.matcher(name).matches()).findFirst();
    }
}
