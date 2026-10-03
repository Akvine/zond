package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.List;

/**
 * @param root        что сканировали
 * @param filesCount  сколько файлов успешно разобрано
 * @param rulesCount  сколько правил было активно
 * @param violations  найденные проблемы
 * @param failedFiles файлы, которые не удалось разобрать
 */
public record ScanResult(
        Path root, int filesCount, int rulesCount, List<Violation> violations, List<Path> failedFiles) {

    public boolean hasViolations() {
        return !violations.isEmpty();
    }
}
