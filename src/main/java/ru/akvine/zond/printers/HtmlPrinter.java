package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Отчет одной веб-страницей: сводка, находки по файлам, отбор по уровню и поиск по тексту.
 * Страница самодостаточна - стили и скрипт внутри, ее можно переслать или приложить к сборке.
 */
@RequiredArgsConstructor
public class HtmlPrinter implements Printer {
    private static final String STYLE = """
            :root {
              --bg: #f6f7f9; --panel: #ffffff; --text: #1c2330; --muted: #5f6b7c; --border: #d9dee6;
              --blocker: #b3261e; --critical: #c2410c; --major: #b45309; --minor: #6b7280; --info: #2563eb;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #14171c; --panel: #1d2128; --text: #e6e9ee; --muted: #9aa4b2; --border: #313743;
                --blocker: #f2857d; --critical: #fb923c; --major: #fbbf24; --minor: #a8b0bd; --info: #7aa7ff;
              }
            }
            * { box-sizing: border-box; }
            body { margin: 0; padding: 24px 16px 48px; background: var(--bg); color: var(--text);
                   font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
            main { max-width: 1200px; margin: 0 auto; }
            h1 { margin: 0 0 4px; font-size: 22px; }
            .path { color: var(--muted); word-break: break-all; margin-bottom: 16px; }
            .stats { display: flex; flex-wrap: wrap; gap: 8px 24px; margin-bottom: 16px; color: var(--muted); }
            .stats b { color: var(--text); font-variant-numeric: tabular-nums; }
            .filters { position: sticky; top: 0; z-index: 1; display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
                       padding: 12px 0; background: var(--bg); border-bottom: 1px solid var(--border); margin-bottom: 16px; }
            .level { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border: 1px solid var(--border);
                     border-radius: 999px; background: var(--panel); cursor: pointer; user-select: none; }
            .level input { margin: 0; }
            .level b { font-variant-numeric: tabular-nums; }
            .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--color); }
            #search { flex: 1 1 220px; min-width: 0; padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px;
                      background: var(--panel); color: var(--text); font: inherit; }
            #shown { color: var(--muted); }
            details { background: var(--panel); border: 1px solid var(--border); border-radius: 8px; margin-bottom: 12px; }
            summary { padding: 10px 14px; cursor: pointer; font-weight: 600; word-break: break-all; }
            summary span { color: var(--muted); font-weight: 400; }
            .rows { border-top: 1px solid var(--border); }
            .row { display: grid; grid-template-columns: 96px 56px 72px minmax(0, 1fr); gap: 4px 12px; padding: 8px 14px;
                   border-bottom: 1px solid var(--border); }
            .row:last-child { border-bottom: 0; }
            .badge { color: var(--color); font-weight: 600; font-size: 12px; letter-spacing: .02em; }
            .line, .code { color: var(--muted); font-variant-numeric: tabular-nums; }
            .text { min-width: 0; overflow-wrap: anywhere; }
            .rule { color: var(--muted); font-size: 12px; }
            .BLOCKER { --color: var(--blocker); } .CRITICAL { --color: var(--critical); } .MAJOR { --color: var(--major); }
            .MINOR { --color: var(--minor); } .INFO { --color: var(--info); }
            .empty { padding: 32px; text-align: center; color: var(--muted); }
            [hidden] { display: none !important; }
            @media (max-width: 640px) {
              .row { grid-template-columns: auto auto minmax(0, 1fr); }
              .row .text { grid-column: 1 / -1; }
            }
            """;

    private static final String SCRIPT = """
            const rows = [...document.querySelectorAll('.row')];
            const files = [...document.querySelectorAll('details.file')];
            const levels = [...document.querySelectorAll('.level input')];
            const search = document.getElementById('search');
            const shown = document.getElementById('shown');
            const none = document.getElementById('none');
            function apply() {
              const enabled = new Set(levels.filter(box => box.checked).map(box => box.value));
              const query = search.value.trim().toLowerCase();
              let count = 0;
              for (const row of rows) {
                const visible = enabled.has(row.dataset.level) && (!query || row.dataset.text.includes(query));
                row.hidden = !visible;
                if (visible) count++;
              }
              for (const file of files) {
                const total = file.querySelectorAll('.row').length;
                const visible = file.querySelectorAll('.row:not([hidden])').length;
                file.hidden = visible === 0;
                file.querySelector('summary span').textContent =
                    '(' + (visible === total ? total : visible + ' из ' + total) + ')';
              }
              shown.textContent = 'Показано: ' + count + ' из ' + rows.length;
              none.hidden = count > 0;
            }
            levels.forEach(box => box.addEventListener('change', apply));
            search.addEventListener('input', apply);
            apply();
            """;

    private final Path reportFile;
    // Показывать ли уверенность находок
    private final boolean showConfidence;

    public HtmlPrinter(Path reportFile) {
        this(reportFile, true);
    }

    @Override
    public void print(ScanResult result) {
        ReportFiles.write(reportFile, toHtml(result), result);
    }

    private String toHtml(ScanResult result) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Zond: отчет о сканировании</title>\n<style>\n").append(STYLE).append("</style>\n</head>\n")
                .append("<body>\n<main>\n<h1>Zond: отчет о сканировании</h1>\n")
                .append("<div class=\"path\">").append(escape(result.root().toAbsolutePath().normalize().toString()))
                .append("</div>\n");

        appendStats(html, result);
        if (result.hasViolations()) {
            appendFilters(html, result);
            appendFiles(html, result);
            html.append("<div id=\"none\" class=\"empty\" hidden>Под выбранные условия ничего не подходит</div>\n");
        } else {
            html.append("<div class=\"empty\">Проблем не найдено</div>\n");
        }
        appendFailedFiles(html, result);

        html.append("</main>\n");
        if (result.hasViolations()) {
            html.append("<script>\n").append(SCRIPT).append("</script>\n");
        }
        return html.append("</body>\n</html>\n").toString();
    }

    private void appendStats(StringBuilder html, ScanResult result) {
        html.append("<div class=\"stats\">");
        stat(html, "Файлов проверено", result.filesCount() + (result.testsSkipped() ? " (каталоги test пропущены)" : ""));
        stat(html, "Активных правил", result.rulesCount()
                + (result.disabledRulesCount() > 0 ? " (отключено: " + result.disabledRulesCount() + ")" : ""));
        stat(html, "Найдено проблем", String.valueOf(result.violations().size()));
        if (result.suppressedCount() > 0) {
            stat(html, "Скрыто zond:ignore", String.valueOf(result.suppressedCount()));
        }
        html.append("</div>\n");
    }

    private void stat(StringBuilder html, String label, String value) {
        html.append("<span>").append(label).append(": <b>").append(escape(value)).append("</b></span>");
    }

    // Переключатели по уровням с числом находок; уровни без находок не показываются
    private void appendFilters(StringBuilder html, ScanResult result) {
        html.append("<div class=\"filters\">\n");
        for (ErrorLevel level : ErrorLevel.values()) {
            long count = result.violations().stream().filter(violation -> violation.errorLevel() == level).count();
            if (count > 0) {
                html.append("<label class=\"level ").append(level).append("\"><input type=\"checkbox\" value=\"")
                        .append(level).append("\" checked><span class=\"dot\"></span>").append(level)
                        .append(" <b>").append(count).append("</b></label>\n");
            }
        }
        html.append("<input id=\"search\" type=\"search\" placeholder=\"Поиск: файл, код правила, текст\">\n")
                .append("<span id=\"shown\"></span>\n</div>\n");
    }

    private void appendFiles(StringBuilder html, ScanResult result) {
        // Находки уже отсортированы по файлу и строке - группируем в том же порядке
        Map<String, List<Violation>> byFile = new LinkedHashMap<>();
        for (Violation violation : result.violations()) {
            byFile.computeIfAbsent(ReportFiles.relativize(result.root(), violation.file()), file -> new ArrayList<>())
                    .add(violation);
        }

        byFile.forEach((file, violations) -> {
            html.append("<details class=\"file\" open>\n<summary>").append(escape(file))
                    .append(" <span>(").append(violations.size()).append(")</span></summary>\n<div class=\"rows\">\n");
            for (Violation violation : violations) {
                appendRow(html, file, violation);
            }
            html.append("</div>\n</details>\n");
        });
    }

    private void appendRow(StringBuilder html, String file, Violation violation) {
        // Текст для поиска: по нему находку можно найти по файлу, правилу и сообщению
        String confidence = showConfidence ? violation.confidenceOrDefault().getTitle() : "";
        String searchText = String.join(" ", file, violation.ruleCode(), violation.ruleName(), violation.message(),
                        confidence)
                .toLowerCase(Locale.ROOT);
        html.append("<div class=\"row ").append(violation.errorLevel()).append("\" data-level=\"")
                .append(violation.errorLevel()).append("\" data-text=\"").append(escape(searchText)).append("\">")
                .append("<span class=\"badge\">").append(violation.errorLevel()).append("</span>")
                .append("<span class=\"line\">").append(violation.line() > 0 ? ":" + violation.line() : "").append("</span>")
                .append("<span class=\"code\">").append(escape(violation.ruleCode())).append("</span>")
                .append("<span class=\"text\">").append(escape(violation.message()))
                .append("<div class=\"rule\">").append(escape(describe(violation.errorType()))).append(" · ")
                .append(confidence.isEmpty() ? "" : confidence + " · ")
                .append(escape(violation.ruleName())).append("</div></span></div>\n");
    }

    private void appendFailedFiles(StringBuilder html, ScanResult result) {
        if (result.failedFiles().isEmpty()) {
            return;
        }
        html.append("<details>\n<summary>Не удалось разобрать <span>(").append(result.failedFiles().size())
                .append(")</span></summary>\n<div class=\"rows\">\n");
        for (Path file : result.failedFiles()) {
            html.append("<div class=\"row\"><span class=\"text\" style=\"grid-column: 1 / -1\">")
                    .append(escape(ReportFiles.relativize(result.root(), file))).append("</span></div>\n");
        }
        html.append("</div>\n</details>\n");
    }

    private String describe(ErrorType type) {
        return type.getDescription() == null ? type.name() : type.getDescription();
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
