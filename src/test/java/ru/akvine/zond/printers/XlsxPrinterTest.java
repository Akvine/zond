package ru.akvine.zond.printers;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxPrinterTest {
    @TempDir
    Path dir;

    @Test
    void writesSummaryAndViolations() throws IOException {
        Path root = dir.resolve("project");
        ScanResult result = new ScanResult(
                root,
                12,
                204,
                3,
                List.of(
                        violation(ErrorLevel.CRITICAL, ErrorType.LOGICAL, "jr:1", "CheckFirstRule",
                                root.resolve("src/main/java/Order.java"), 7, "Первая проблема"),
                        violation(ErrorLevel.INFO, ErrorType.CODE_SMELL, "jr:40", "CheckMagicNumberRule",
                                root.resolve("src/main/java/Order.java"), 9, "Магическое число 42"),
                        violation(ErrorLevel.INFO, ErrorType.CODE_SMELL, "jr:40", "CheckMagicNumberRule",
                                root.resolve("src/main/java/Item.java"), 3, "Магическое число 7")),
                2,
                true,
                List.of(root.resolve("src/main/java/Broken.java")));
        Path report = dir.resolve("out/report.xlsx");

        new XlsxPrinter(report).print(result);

        try (InputStream input = Files.newInputStream(report); Workbook workbook = new XSSFWorkbook(input)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);

            List<String> summary = rows(workbook.getSheet("Сводка"));
            assertThat(summary).contains(
                    "Файлов проверено|12",
                    "Каталоги test|пропущены",
                    "Активных правил|204",
                    "Правил отключено настройками|3",
                    "Найдено проблем|3",
                    "Скрыто комментариями zond:ignore|2",
                    "CRITICAL|1",
                    "MAJOR|0",
                    "INFO|2");
            // Правило с наибольшим числом проблем идет первым
            assertThat(summary).containsSubsequence("CheckMagicNumberRule|2|jr:40", "CheckFirstRule|1|jr:1");

            assertThat(rows(workbook.getSheet("Проблемы"))).containsExactly(
                    "Уровень|Тип|Код|Правило|Файл|Строка|Сообщение|Уверенность",
                    "CRITICAL|Логические ошибки|jr:1|CheckFirstRule|" + path("src/main/java/Order.java")
                            + "|7|Первая проблема|вероятно",
                    "INFO|Качество кода|jr:40|CheckMagicNumberRule|" + path("src/main/java/Order.java")
                            + "|9|Магическое число 42|вероятно",
                    "INFO|Качество кода|jr:40|CheckMagicNumberRule|" + path("src/main/java/Item.java")
                            + "|3|Магическое число 7|вероятно");

            assertThat(rows(workbook.getSheet("Не разобраны")))
                    .containsExactly("Файл", path("src/main/java/Broken.java"));
        }
    }

    @Test
    void writesReportWithoutViolations() throws IOException {
        Path report = dir.resolve("empty.xlsx");

        new XlsxPrinter(report).print(new ScanResult(dir, 5, 10, 0, List.of(), 0, false, List.of()));

        try (InputStream input = Files.newInputStream(report); Workbook workbook = new XSSFWorkbook(input)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(rows(workbook.getSheet("Проблемы"))).containsExactly("Уровень|Тип|Код|Правило|Файл|Строка|Сообщение|Уверенность");
            assertThat(rows(workbook.getSheet("Сводка"))).contains("Найдено проблем|0", "Каталоги test|проверены");
        }
    }

    @Test
    void factoryChoosesPrinterByExtension() {
        PrinterFactory factory = new PrinterFactory(new ReportFormatter());

        assertThat(factory.create(null)).isInstanceOf(ConsolePrinter.class);
        assertThat(factory.create(dir.resolve("report.txt"))).isInstanceOf(FilePrinter.class);
        assertThat(factory.create(dir.resolve("report.xlsx"))).isInstanceOf(XlsxPrinter.class);
        assertThat(factory.create(dir.resolve("REPORT.XLSX"))).isInstanceOf(XlsxPrinter.class);
    }

    private Violation violation(
            ErrorLevel level, ErrorType type, String code, String name, Path file, int line, String message) {
        return new Violation(level, type, code, name, file, line, message);
    }

    // Строки листа: значения ячеек через |, числа без дробной части
    private List<String> rows(Sheet sheet) {
        List<String> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            row.forEach(cell -> cells.add(switch (cell.getCellType()) {
                case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                default -> cell.getStringCellValue();
            }));
            rows.add(String.join("|", cells));
        }
        return rows;
    }

    // Разделитель каталогов зависит от операционной системы
    private String path(String unixPath) {
        return Path.of(unixPath).toString();
    }
}
