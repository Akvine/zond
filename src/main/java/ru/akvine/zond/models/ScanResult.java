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
 * @param lowConfidenceCount сколько проблем скрыто порогом уверенности
 * @param timings            сколько работало каждое правило, в порядке запуска
 * @param checkNanos         сколько заняла проверка: от запуска первого правила до конца последнего,
 *                           без загрузки и разбора кода
 * @param changedFilesCount  сколько измененных файлов проверено, если находки показаны только по ним;
 *                           null - проверен весь проект
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
        int lowConfidenceCount,
        List<RuleTiming> timings,
        long checkNanos,
        Integer changedFilesCount) {

    public ScanResult(
            Path root, int filesCount, int rulesCount, int disabledRulesCount, List<Violation> violations,
            int suppressedCount, boolean testsSkipped, List<Path> failedFiles, int lowConfidenceCount,
            List<RuleTiming> timings, long checkNanos) {
        this(root, filesCount, rulesCount, disabledRulesCount, violations, suppressedCount, testsSkipped, failedFiles,
                lowConfidenceCount, timings, checkNanos, null);
    }

    public ScanResult(
            Path root, int filesCount, int rulesCount, int disabledRulesCount, List<Violation> violations,
            int suppressedCount, boolean testsSkipped, List<Path> failedFiles, int lowConfidenceCount) {
        this(root, filesCount, rulesCount, disabledRulesCount, violations, suppressedCount, testsSkipped, failedFiles,
                lowConfidenceCount, List.of(), 0);
    }

    public ScanResult(
            Path root, int filesCount, int rulesCount, int disabledRulesCount, List<Violation> violations,
            int suppressedCount, boolean testsSkipped, List<Path> failedFiles) {
        this(root, filesCount, rulesCount, disabledRulesCount, violations, suppressedCount, testsSkipped, failedFiles, 0);
    }

    public boolean hasViolations() {
        return !violations.isEmpty();
    }
}
