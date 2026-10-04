package ru.akvine.zond.printers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.enums.ReportFormat;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlAndSarifPrinterTest {
    @TempDir
    Path dir;

    @Test
    void formatIsChosenByExtension() {
        assertThat(ReportFormat.of("report.html")).isEqualTo(ReportFormat.HTML);
        assertThat(ReportFormat.of("report.SARIF")).isEqualTo(ReportFormat.SARIF);
        assertThat(ReportFormat.of("report.sarif.json")).isEqualTo(ReportFormat.SARIF);
        assertThat(ReportFormat.of("report.xlsx")).isEqualTo(ReportFormat.XLSX);
        assertThat(ReportFormat.of("report.log")).isEqualTo(ReportFormat.TXT);

        PrinterFactory factory = new PrinterFactory(new ReportFormatter());
        assertThat(factory.create(dir.resolve("report.html"))).isInstanceOf(HtmlPrinter.class);
        assertThat(factory.create(dir.resolve("report.sarif"))).isInstanceOf(SarifPrinter.class);
        assertThat(factory.create(dir.resolve("report.txt"))).isInstanceOf(FilePrinter.class);
        assertThat(factory.create(null)).isInstanceOf(ConsolePrinter.class);
    }

    @Test
    void sarifListsRulesAndResults() throws IOException {
        Path report = dir.resolve("out/report.sarif");
        new SarifPrinter(report).print(result());

        String sarif = Files.readString(report);
        assertThat(sarif)
                .contains("\"version\": \"2.1.0\"")
                .contains("\"name\": \"Zond\"")
                // Правило описано один раз, хотя находок по нему две
                .containsOnlyOnce("\"id\": \"jr:40\"")
                .contains("\"ruleId\": \"jr:1\"")
                .contains("\"level\": \"error\"")
                .contains("\"level\": \"note\"")
                // Путь от корня сканирования через прямую черту - одинаково на любой системе
                .contains("\"uri\": \"src/main/java/Order.java\"")
                .contains("\"uriBaseId\": \"%SRCROOT%\"")
                .contains("\"startLine\": 7")
                // Кавычки, обратная черта и перевод строки в сообщении экранированы
                .contains("Путь \\\"C:\\\\temp\\\" и\\nвторая строка");
        assertThat(sarif).doesNotContain("\"startLine\": 0");
        assertBalanced(sarif);
    }

    @Test
    void sarifWithoutViolationsIsStillValid() throws IOException {
        Path report = dir.resolve("empty.sarif");
        new SarifPrinter(report).print(new ScanResult(dir, 5, 10, 0, List.of(), 0, false, List.of()));

        String sarif = Files.readString(report);
        assertThat(sarif).contains("\"rules\": []").contains("\"results\": []");
        assertBalanced(sarif);
    }

    @Test
    void htmlShowsSummaryAndViolationsByFile() throws IOException {
        Path report = dir.resolve("out/report.html");
        new HtmlPrinter(report).print(result());

        String html = Files.readString(report);
        assertThat(html)
                .startsWith("<!doctype html>")
                .contains("Файлов проверено: <b>12 (каталоги test пропущены)</b>")
                .contains("Найдено проблем: <b>4</b>")
                .contains("Скрыто zond:ignore: <b>2</b>")
                // Переключатели есть только для уровней, по которым что-то найдено
                .contains("value=\"CRITICAL\" checked")
                .contains("value=\"INFO\" checked")
                .doesNotContain("value=\"MAJOR\"")
                .contains("<details class=\"file\" open>")
                .contains("<summary>src/main/java/Order.java <span>(3)</span></summary>")
                .contains("<summary>src/main/java/Item.java <span>(1)</span></summary>")
                .contains("Не удалось разобрать <span>(1)</span>")
                // Разметка из сообщения в страницу не попадает
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void htmlWithoutViolationsHasNoFilters() throws IOException {
        Path report = dir.resolve("empty.html");
        new HtmlPrinter(report).print(new ScanResult(dir, 5, 10, 0, List.of(), 0, false, List.of()));

        assertThat(Files.readString(report))
                .contains("Проблем не найдено")
                .doesNotContain("id=\"search\"")
                .doesNotContain("<script>");
    }

    private ScanResult result() {
        Path root = dir.resolve("project");
        Path order = root.resolve("src/main/java/Order.java");
        return new ScanResult(
                root,
                12,
                204,
                3,
                List.of(
                        violation(ErrorLevel.CRITICAL, "jr:1", "CheckFirstRule", order, 7, "Первая проблема"),
                        violation(ErrorLevel.INFO, "jr:40", "CheckMagicNumberRule", order, 9,
                                "Путь \"C:\\temp\" и\nвторая строка"),
                        violation(ErrorLevel.INFO, "jr:40", "CheckMagicNumberRule", order, 0,
                                "<script>alert(1)</script>"),
                        violation(ErrorLevel.CRITICAL, "jr:1", "CheckFirstRule",
                                root.resolve("src/main/java/Item.java"), 3, "Вторая проблема")),
                2,
                true,
                List.of(root.resolve("src/main/java/Broken.java")));
    }

    private Violation violation(ErrorLevel level, String code, String rule, Path file, int line, String message) {
        return new Violation(level, ErrorType.LOGICAL, code, rule, file, line, message);
    }

    // Грубая проверка целостности JSON: скобки вне строк закрыты в том же порядке, в каком открыты
    private void assertBalanced(String json) {
        StringBuilder stack = new StringBuilder();
        boolean inString = false;
        for (int index = 0; index < json.length(); index++) {
            char symbol = json.charAt(index);
            if (inString) {
                if (symbol == '\\') {
                    index++;
                } else if (symbol == '"') {
                    inString = false;
                }
            } else if (symbol == '"') {
                inString = true;
            } else if (symbol == '{' || symbol == '[') {
                stack.append(symbol);
            } else if (symbol == '}' || symbol == ']') {
                assertThat(stack).as("лишняя закрывающая скобка на позиции " + index).isNotEmpty();
                char open = stack.charAt(stack.length() - 1);
                assertThat(open == '{' ? '}' : ']').isEqualTo(symbol);
                stack.setLength(stack.length() - 1);
            }
        }
        assertThat(inString).as("строка не закрыта").isFalse();
        assertThat(stack).as("скобки не закрыты").isEmpty();
    }
}
