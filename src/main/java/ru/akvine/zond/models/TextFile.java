package ru.akvine.zond.models;

import ru.akvine.zond.enums.TextFileType;

import java.nio.file.Path;
import java.util.List;

/**
 * Файл проекта помимо Java-кода и настроек Spring: SQL-миграция, журнал Liquibase, pom.xml, build.gradle,
 * Dockerfile, docker-compose, настройки логирования, манифест Kubernetes, файл CI, messages*.properties
 *
 * @param lines строки файла по порядку; номер строки в находке - индекс плюс один
 * @param type  что это за файл
 */
public record TextFile(Path path, List<String> lines, TextFileType type) {

    public String name() {
        return path.getFileName().toString();
    }
}
