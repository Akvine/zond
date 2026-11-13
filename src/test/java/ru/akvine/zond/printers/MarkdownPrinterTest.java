package ru.akvine.zond.printers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownPrinterTest {
    @TempDir
    Path dir;

    @Test
    void markdownShowsSummaryAndViolationsByFile() throws IOException {
        Path report = dir.resolve("out/report.md");
        new MarkdownPrinter(report).print(result());

        assertThat(Files.readString(report))
                .startsWith("# Zond: отчет о сканировании")
                .contains("- Файлов проверено: 12 (каталоги test пропущены)")
                .contains("- Активных правил: 204 (отключено настройками: 3)")
                .contains("- Найдено проблем: 3 (скрыто комментариями zond:ignore: 2)")
                // Значения без находок в сводку не попадают
                .contains("- По уровням: CRITICAL: 2, INFO: 1")
                .contains("Логические ошибки: 2")
                .contains("- По уверенности: подтверждено: 1, вероятно: 2")
                // Путь от корня сканирования, находки - таблицей под своим файлом
                .contains("### `src/main/java/Order.java` (2)")
                .contains("| Строка | Уровень | Правило | Уверенность | Описание |")
                .contains("| 7 | CRITICAL | `jr:1` FirstRule | подтверждено | Первая проблема |")
                // Черта, угловые скобки и перевод строки из сообщения таблицу не ломают
                .contains("| 9 | INFO | `jr:40` MagicNumberRule | вероятно | List&lt;String&gt; a \\| b и вторая строка |")
                .contains("### `src/main/java/Item.java` (1)")
                // Находка без номера строки: ячейка пустая, а не 0
                .contains("|  | CRITICAL | `jr:1` FirstRule | вероятно | Вторая проблема |")
                .contains("## Не удалось разобрать файлы (1)")
                .contains("- `src/main/java/Broken.java`");
    }

    @Test
    void markdownHidesConfidenceWhenSwitchedOff() throws IOException {
        Path report = dir.resolve("report.md");
        new MarkdownPrinter(report, false).print(result());

        assertThat(Files.readString(report))
                .contains("| Строка | Уровень | Правило | Описание |")
                .contains("| 7 | CRITICAL | `jr:1` FirstRule | Первая проблема |")
                .doesNotContain("веренност")
                .doesNotContain("подтверждено");
    }

    @Test
    void markdownWithoutViolationsHasNoTables() throws IOException {
        Path report = dir.resolve("empty.md");
        new MarkdownPrinter(report).print(new ScanResult(dir, 5, 10, 0, List.of(), 0, false, List.of()));

        assertThat(Files.readString(report))
                .contains("- Найдено проблем: 0")
                .contains("Проблем не найдено.")
                .doesNotContain("## Находки")
                .doesNotContain("| Строка |");
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
                        new Violation(ErrorLevel.CRITICAL, ErrorType.LOGICAL, "jr:1", "FirstRule", order, 7,
                                "Первая проблема", Confidence.CONFIRMED),
                        new Violation(ErrorLevel.INFO, ErrorType.CODE_SMELL, "jr:40", "MagicNumberRule", order, 9,
                                "List<String> a | b\nи вторая строка"),
                        new Violation(ErrorLevel.CRITICAL, ErrorType.LOGICAL, "jr:1", "FirstRule",
                                root.resolve("src/main/java/Item.java"), 0, "Вторая проблема")),
                2,
                true,
                List.of(root.resolve("src/main/java/Broken.java")));
    }
}
