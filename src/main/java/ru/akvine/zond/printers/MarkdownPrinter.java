package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import ru.akvine.zond.config.ZondVersion;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Отчет в Markdown: шапка со сводкой и находки таблицами по файлам. Файл читается и как обычный текст,
 * и отрисованным - в GitLab, GitHub, вики или описании merge request.
 */
@RequiredArgsConstructor
public class MarkdownPrinter implements Printer {
    private static final String NEW_LINE = "\n";

    private final Path reportFile;
    // Показывать ли уверенность находок
    private final boolean showConfidence;

    public MarkdownPrinter(Path reportFile) {
        this(reportFile, true);
    }

    @Override
    public void print(ScanResult result) {
        ReportFiles.write(reportFile, toMarkdown(result), result);
    }

    private String toMarkdown(ScanResult result) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Zond: отчет о сканировании").append(NEW_LINE).append(NEW_LINE);
        appendSummary(markdown, result);
        if (result.hasViolations()) {
            appendFiles(markdown, result);
        } else {
            markdown.append(NEW_LINE).append("Проблем не найдено.").append(NEW_LINE);
        }
        appendFailedFiles(markdown, result);
        return markdown.toString();
    }

    private void appendSummary(StringBuilder markdown, ScanResult result) {
        item(markdown, "Версия Zond", ZondVersion.current());
        item(markdown, "Путь", code(result.root().toAbsolutePath().normalize().toString()));
        item(markdown, "Файлов проверено",
                result.filesCount() + (result.testsSkipped() ? " (каталоги test пропущены)" : ""));
        if (result.changedFilesCount() != null) {
            item(markdown, "Находки показаны только в измененных файлах", String.valueOf(result.changedFilesCount()));
        }
        item(markdown, "Активных правил", result.rulesCount() + (result.disabledRulesCount() > 0
                ? " (отключено настройками: " + result.disabledRulesCount() + ")" : ""));
        item(markdown, "Найдено проблем", result.violations().size() + (result.suppressedCount() > 0
                ? " (скрыто комментариями zond:ignore: " + result.suppressedCount() + ")" : ""));
        if (result.hasViolations()) {
            item(markdown, "По уровням", counts(result, ErrorLevel.values(), Violation::errorLevel, Enum::name));
            item(markdown, "По типам", counts(result, ErrorType.values(), Violation::errorType, this::describe));
            if (showConfidence) {
                item(markdown, "По уверенности",
                        counts(result, Confidence.values(), Violation::confidenceOrDefault, Confidence::getTitle));
            }
        }
        if (result.lowConfidenceCount() > 0) {
            item(markdown, "Скрыто находок с уверенностью ниже заданной", String.valueOf(result.lowConfidenceCount()));
        }
    }

    private void item(StringBuilder markdown, String label, String value) {
        markdown.append("- ").append(label).append(": ").append(value).append(NEW_LINE);
    }

    // CRITICAL: 2, INFO: 5 - в порядке объявления значений, без тех, по которым находок нет
    private <T> String counts(ScanResult result, T[] values, Function<Violation, T> property, Function<T, String> title) {
        return Arrays.stream(values)
                .map(value -> Map.entry(value, result.violations().stream()
                        .filter(violation -> property.apply(violation) == value)
                        .count()))
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> title.apply(entry.getKey()) + ": " + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private void appendFiles(StringBuilder markdown, ScanResult result) {
        // Находки уже отсортированы по файлу и строке - группируем в том же порядке
        Map<String, List<Violation>> byFile = new LinkedHashMap<>();
        for (Violation violation : result.violations()) {
            byFile.computeIfAbsent(ReportFiles.relativize(result.root(), violation.file()), file -> new ArrayList<>())
                    .add(violation);
        }

        markdown.append(NEW_LINE).append("## Находки").append(NEW_LINE);
        byFile.forEach((file, violations) -> {
            markdown.append(NEW_LINE).append("### ").append(code(file)).append(" (").append(violations.size())
                    .append(')').append(NEW_LINE).append(NEW_LINE);
            markdown.append(showConfidence
                    ? "| Строка | Уровень | Правило | Уверенность | Описание |" + NEW_LINE + "|---:|---|---|---|---|"
                    : "| Строка | Уровень | Правило | Описание |" + NEW_LINE + "|---:|---|---|---|").append(NEW_LINE);
            for (Violation violation : violations) {
                appendRow(markdown, violation);
            }
        });
    }

    private void appendRow(StringBuilder markdown, Violation violation) {
        markdown.append("| ").append(violation.line() > 0 ? String.valueOf(violation.line()) : "")
                .append(" | ").append(violation.errorLevel())
                .append(" | ").append(code(violation.ruleCode())).append(' ').append(cell(violation.ruleName()));
        if (showConfidence) {
            markdown.append(" | ").append(violation.confidenceOrDefault().getTitle());
        }
        markdown.append(" | ").append(cell(violation.message())).append(" |").append(NEW_LINE);
    }

    private void appendFailedFiles(StringBuilder markdown, ScanResult result) {
        if (result.failedFiles().isEmpty()) {
            return;
        }
        markdown.append(NEW_LINE).append("## Не удалось разобрать файлы (").append(result.failedFiles().size())
                .append(')').append(NEW_LINE).append(NEW_LINE);
        for (Path file : result.failedFiles()) {
            markdown.append("- ").append(code(ReportFiles.relativize(result.root(), file))).append(NEW_LINE);
        }
    }

    private String describe(ErrorType type) {
        return type.getDescription() == null ? type.name() : type.getDescription();
    }

    // Путь или код правила моноширинным шрифтом; обратная кавычка внутри текста требует двойных снаружи
    private String code(String text) {
        return text.contains("`") ? "`` " + text + " ``" : "`" + text + "`";
    }

    /**
     * Текст для ячейки таблицы. Вертикальная черта и перевод строки ломают саму таблицу, угловые скобки
     * (например, {@code List<String>}) отрисовка принимает за разметку, звездочка и обратная кавычка
     * включают выделение - все это выводится как обычные символы.
     */
    private String cell(String text) {
        return text.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("*", "\\*")
                .replace("`", "\\`")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }
}
