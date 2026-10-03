package ru.akvine.zond.printers;

import org.springframework.stereotype.Component;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;

@Component
public class ReportFormatter {

    public String format(ScanResult result) {
        String newLine = System.lineSeparator();
        StringBuilder report = new StringBuilder();
        report.append("Zond: отчет о сканировании").append(newLine);
        report.append("Путь: ").append(result.root().toAbsolutePath().normalize()).append(newLine);
        report.append("Файлов проверено: ").append(result.filesCount()).append(newLine);
        report.append("Активных правил: ").append(result.rulesCount()).append(newLine);
        report.append("Найдено проблем: ").append(result.violations().size()).append(newLine);

        if (result.hasViolations()) {
            report.append(newLine);
            for (Violation violation : result.violations()) {
                report
                        .append("[").append(violation.errorLevel()).append("] ")
                        .append("[").append(violation.errorType()).append("] ")
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
}
