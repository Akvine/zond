package ru.akvine.zond.printers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.config.ZondVersion;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class ReportFormatter {
    // Показывать ли уверенность: в каждой находке и сводкой в шапке
    private final boolean showConfidence;

    @Autowired
    public ReportFormatter(ZondSettings settings) {
        this(settings.reportConfidence());
    }

    public ReportFormatter() {
        this(true);
    }

    public ReportFormatter(boolean showConfidence) {
        this.showConfidence = showConfidence;
    }

    public boolean showsConfidence() {
        return showConfidence;
    }

    public String format(ScanResult result) {
        String newLine = System.lineSeparator();
        StringBuilder report = new StringBuilder();
        report.append("Zond: отчет о сканировании").append(newLine);
        report.append("Версия Zond: ").append(ZondVersion.current()).append(newLine);
        report.append("Путь: ").append(result.root().toAbsolutePath().normalize()).append(newLine);
        report.append("Файлов проверено: ").append(result.filesCount());
        if (result.testsSkipped()) {
            report.append(" (каталоги test пропущены)");
        }
        report.append(newLine);
        if (result.changedFilesCount() != null) {
            report.append("Находки показаны только в измененных файлах: ").append(result.changedFilesCount()).append(newLine);
        }
        report.append("Активных правил: ").append(result.rulesCount());
        if (result.disabledRulesCount() > 0) {
            report.append(" (отключено настройками: ").append(result.disabledRulesCount()).append(')');
        }
        report.append(newLine);

        report.append("Найдено проблем: ").append(result.violations().size());
        if (result.suppressedCount() > 0) {
            report.append(" (скрыто комментариями zond:ignore: ").append(result.suppressedCount()).append(')');
        }
        report.append(newLine);
        if (showConfidence && result.hasViolations()) {
            report.append("По уверенности: ").append(describeConfidence(result)).append(newLine);
        }
        if (result.lowConfidenceCount() > 0) {
            report.append("Скрыто находок с уверенностью ниже заданной: ")
                    .append(result.lowConfidenceCount()).append(newLine);
        }

        if (result.hasViolations()) {
            report.append(newLine);
            for (Violation violation : result.violations()) {
                report
                        .append("[").append(violation.errorLevel()).append("] ")
                        .append("[").append(violation.errorType()).append("] ")
                        .append(showConfidence ? "[" + violation.confidenceOrDefault().getTitle() + "] " : "")
                        .append('[').append(violation.ruleCode()).append("] ")
                        .append(violation.file()).append(':').append(violation.line())
                        .append(" - ").append(violation.message())
                        .append(" (").append(violation.ruleName()).append(')')
                        .append(newLine);
            }
        }

        if (!result.failedFiles().isEmpty()) {
            report.append(newLine).append("Не удалось разобрать файлы: ")
                    .append(result.failedFiles().size()).append(newLine);
            for (Path file : result.failedFiles()) {
                report.append("  ").append(file).append(newLine);
            }
        }
        return report.toString();
    }

    // подтверждено: 12, вероятно: 30, подозрение: 5
    private String describeConfidence(ScanResult result) {
        return Arrays.stream(Confidence.values())
                .map(confidence -> confidence.getTitle() + ": " + result.violations().stream()
                        .filter(violation -> violation.confidenceOrDefault() == confidence)
                        .count())
                .collect(Collectors.joining(", "));
    }
}
