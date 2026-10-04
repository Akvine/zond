package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.List;

/**
 * Файл проекта, который проверяется как текст: SQL-миграция, pom.xml, build.gradle, Dockerfile,
 * messages*.properties
 *
 * @param lines строки файла по порядку; номер строки в находке - индекс плюс один
 */
public record TextFile(Path path, List<String> lines) {

    public String name() {
        return path.getFileName().toString();
    }
}
