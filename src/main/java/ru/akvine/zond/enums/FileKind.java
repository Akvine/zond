package ru.akvine.zond.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Виды файлов, которые проверяются помимо Java-кода. Каждый вид можно отключить своей настройкой:
 * zond.scan.sql=false либо --scan-sql=false
 */
@AllArgsConstructor
@Getter
public enum FileKind {
    SQL("sql", "Миграции БД (*.sql, журналы Liquibase в XML и YAML)"),
    BUILD_FILES("build-files", "Файлы сборки (pom.xml, build.gradle)"),
    DOCKER("docker", "Dockerfile и docker-compose"),
    CONFIG("config", "Настройки Spring (application*.properties, *.yml)"),
    MESSAGES("messages", "Файлы сообщений (messages*.properties)"),
    LOGGING("logging", "Настройки логирования (logback*.xml, log4j2*.xml)"),
    KUBERNETES("kubernetes", "Манифесты Kubernetes"),
    CI("ci", "Файлы CI (.gitlab-ci.yml, .github/workflows)");

    private static final String PROPERTY_PREFIX = "zond.scan.";
    private static final String OPTION_PREFIX = "scan-";

    private final String key;
    private final String description;

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
}
