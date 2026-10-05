package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Отчет в формате SARIF 2.1.0: его понимают GitHub Code Scanning, GitLab, Azure DevOps и IDE -
 * находки показываются прямо в коде и в pull request.
 */
@RequiredArgsConstructor
public class SarifPrinter implements Printer {
    private static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String VERSION = "2.1.0";
    private static final String TOOL_NAME = "Zond";

    // Корень проверенного проекта: по нему приемник отчета находит файлы у себя
    private static final String SOURCE_ROOT = "%SRCROOT%";

    // В SARIF три уровня: два самых строгих у zond - ошибка, средний - предупреждение, остальные - замечание
    private static final String ERROR = "error";
    private static final String WARNING = "warning";
    private static final String NOTE = "note";

    private static final String INDENT = "  ";

    private final Path reportFile;
    // Показывать ли уверенность находок
    private final boolean showConfidence;

    public SarifPrinter(Path reportFile) {
        this(reportFile, true);
    }

    @Override
    public void print(ScanResult result) {
        ReportFiles.write(reportFile, toJson(result), result);
    }

    private String toJson(ScanResult result) {
        // Правило описывается один раз, находки ссылаются на него по коду
        Map<String, Violation> rules = new LinkedHashMap<>();
        for (Violation violation : result.violations()) {
            rules.putIfAbsent(violation.ruleCode(), violation);
        }

        List<String> ruleItems = new ArrayList<>();
        for (Violation rule : rules.values()) {
            ruleItems.add(object(6,
                    pair("id", string(rule.ruleCode())),
                    pair("name", string(rule.ruleName())),
                    pair("defaultConfiguration", "{ " + pair("level", string(levelOf(rule.errorLevel()))) + " }"),
                    pair("properties", "{ " + pair("level", string(rule.errorLevel().name())) + ", "
                            + pair("type", string(rule.errorType().name())) + " }")));
        }

        List<String> ruleIds = new ArrayList<>(rules.keySet());
        List<String> resultItems = new ArrayList<>();
        for (Violation violation : result.violations()) {
            List<String> fields = new ArrayList<>(List.of(
                    pair("ruleId", string(violation.ruleCode())),
                    pair("ruleIndex", String.valueOf(ruleIds.indexOf(violation.ruleCode()))),
                    pair("level", string(levelOf(violation.errorLevel()))),
                    pair("message", "{ " + pair("text", string(violation.message())) + " }")));
            if (showConfidence) {
                fields.add(pair("properties",
                        "{ " + pair("confidence", string(violation.confidenceOrDefault().name())) + " }"));
            }
            fields.add(pair("locations", "[ " + location(result.root(), violation) + " ]"));
            resultItems.add(object(4, fields.toArray(String[]::new)));
        }

        String driver = object(4,
                pair("name", string(TOOL_NAME)),
                pair("rules", array(5, ruleItems)));
        String run = object(2,
                pair("tool", "{\n" + INDENT.repeat(4) + pair("driver", driver) + "\n" + INDENT.repeat(3) + "}"),
                pair("results", array(3, resultItems)));
        return object(0,
                pair("$schema", string(SCHEMA)),
                pair("version", string(VERSION)),
                pair("runs", "[\n" + INDENT.repeat(2) + run + "\n" + INDENT + "]")) + "\n";
    }

    private String location(Path root, Violation violation) {
        String artifact = "{ " + pair("uri", string(ReportFiles.relativize(root, violation.file()))) + ", "
                + pair("uriBaseId", string(SOURCE_ROOT)) + " }";
        // Строки в SARIF считаются с единицы; у находки на весь файл строки нет
        String region = violation.line() > 0
                ? ", " + pair("region", "{ " + pair("startLine", String.valueOf(violation.line())) + " }")
                : "";
        return "{ " + pair("physicalLocation", "{ " + pair("artifactLocation", artifact) + region + " }") + " }";
    }

    private String levelOf(ErrorLevel level) {
        return switch (level) {
            case BLOCKER, CRITICAL -> ERROR;
            case MAJOR -> WARNING;
            default -> NOTE;
        };
    }

    private String object(int depth, String... pairs) {
        String inner = INDENT.repeat(depth + 1);
        return "{\n" + inner + String.join(",\n" + inner, pairs) + "\n" + INDENT.repeat(depth) + "}";
    }

    private String array(int depth, List<String> items) {
        if (items.isEmpty()) {
            return "[]";
        }
        String inner = INDENT.repeat(depth + 1);
        return "[\n" + inner + String.join(",\n" + inner, items) + "\n" + INDENT.repeat(depth) + "]";
    }

    private String pair(String name, String value) {
        return string(name) + ": " + value;
    }

    // Строка JSON: кавычки, обратная косая черта и управляющие символы экранируются
    private String string(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            switch (symbol) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (symbol < ' ') {
                        escaped.append(String.format("\\u%04x", (int) symbol));
                    } else {
                        escaped.append(symbol);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
