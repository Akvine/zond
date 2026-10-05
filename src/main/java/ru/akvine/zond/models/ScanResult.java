package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.List;

/**
 * @param root               что сканировали
 * @param filesCount         сколько файлов успешно разобрано
 * @param rulesCount         сколько правил было активно
 * @param disabledRulesCount сколько правил отключено настройками: списком правил или порогом уровня
 * @param violations         найденные проблемы
 * @param suppressedCount    сколько проблем скрыто комментариями zond:ignore
 * @param testsSkipped       каталоги test не проверялись
 * @param failedFiles        файлы, которые не удалось разобрать
 */
public record ScanResult(
        Path root,
        int filesCount,
        int rulesCount,
        int disabledRulesCount,
        List<Violation> violations,
        int suppressedCount,
        boolean testsSkipped,
        List<Path> failedFiles,
        int lowConfidenceCount) {

    public ScanResult(
            Path root, int filesCount, int rulesCount, int disabledRulesCount, List<Violation> violations,
            int suppressedCount, boolean testsSkipped, List<Path> failedFiles) {
        this(root, filesCount, rulesCount, disabledRulesCount, violations, suppressedCount, testsSkipped, failedFiles, 0);
    }

    public boolean hasViolations() {
        return !violations.isEmpty();
    }
}
