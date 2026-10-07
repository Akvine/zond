package ru.akvine.zond.printers;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.DurationUnit;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.enums.TimingZone;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.RuleTiming;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.TimingThresholds;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.AutowiredOnStaticFieldRule;
import ru.akvine.zond.rules.logical.TransactionOnPrivateMethodRule;
import ru.akvine.zond.services.Scanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatisticReportPrinterTest {
    private static final long MILLI = 1_000_000L;
    private static final String RED = "FFFFC7CE";
    private static final String YELLOW = "FFFFEB9C";
    private static final String GREEN = "FFC6EFCE";
    private static final int TIME_COLUMNS = 3;
    private static final String TOTAL = "Всего";

    @Test
    void thresholdsChooseTheHeaviestReachedColor() {
        TimingThresholds thresholds = TimingThresholds.parse("20", "5", "1");

        assertThat(thresholds.zoneOf(20)).isEqualTo(TimingZone.RED);
        assertThat(thresholds.zoneOf(19.99)).isEqualTo(TimingZone.YELLOW);
        assertThat(thresholds.zoneOf(5)).isEqualTo(TimingZone.YELLOW);
        assertThat(thresholds.zoneOf(1)).isEqualTo(TimingZone.GREEN);
        assertThat(thresholds.zoneOf(0.5)).isEqualTo(TimingZone.NONE);
        // Без настроек: красный от 10 %, желтый от 3 %, зеленые все остальные
        assertThat(TimingThresholds.parse("", null, " ")).isEqualTo(new TimingThresholds(10, 3, 0));
        assertThat(TimingThresholds.parse("7,5", "2.5", "")).isEqualTo(new TimingThresholds(7.5, 2.5, 0));
    }

    @Test
    void wrongThresholdsAreRejected() {
        assertThatThrownBy(() -> TimingThresholds.parse("many", "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("many");
        assertThatThrownBy(() -> TimingThresholds.parse("120", "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("от 0 до 100");
        assertThatThrownBy(() -> TimingThresholds.parse("5", "10", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("по убыванию");
    }

    @Test
    void rowsShowTimeAndFindingsOfEachRule(@TempDir Path dir) throws IOException {
        // Замечаний восемь: шесть у медленного правила и два у легкого. Уровни разные, но считаются все вместе
        List<Violation> violations = new ArrayList<>();
        for (int line = 1; line <= 6; line++) {
            violations.add(violation(line % 2 == 0 ? ErrorLevel.CRITICAL : ErrorLevel.INFO, "jr:2", "SlowRule", line));
        }
        violations.add(violation(ErrorLevel.MAJOR, "jr:4", "LightRule", 1));
        violations.add(violation(ErrorLevel.MINOR, "jr:4", "LightRule", 2));
        // Правила вместе работали 1000 мс: их доли - 60 %, 30 %, 8 % и 2 %. Сама проверка в несколько
        // потоков заняла меньше, но на доли это не влияет: в сумме они дают 100 %
        ScanResult result = new ScanResult(dir, 1, 4, 0, violations, 0, false, List.of(), 0, List.of(
                new RuleTiming("jr:1", "FastRule", 20 * MILLI),
                new RuleTiming("jr:2", "SlowRule", 600 * MILLI),
                new RuleTiming("jr:3", "MiddleRule", 300 * MILLI),
                new RuleTiming("jr:4", "LightRule", 80 * MILLI)), 700 * MILLI);
        Path file = dir.resolve("reports").resolve("statistic.xlsx");

        new StatisticReportPrinter(file, DurationUnit.MILLISECONDS, TimingThresholds.parse("50", "10", "5")).print(result);

        try (InputStream input = Files.newInputStream(file); XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(texts(sheet.getRow(0))).containsExactly(
                    "Правило", "Время работы, мс", "Доля от общего времени, %",
                    "Найдено замечаний", "Доля от всех замечаний, %");

            assertRow(sheet, 1, "SlowRule (jr:2)", 600, 60, 6, 75, RED);
            assertRow(sheet, 2, "MiddleRule (jr:3)", 300, 30, 0, 0, YELLOW);
            assertRow(sheet, 3, "LightRule (jr:4)", 80, 8, 2, 25, GREEN);
            // До порога зеленого правило не дотянуло: строка без заливки
            assertRow(sheet, 4, "FastRule (jr:1)", 20, 2, 0, 0, null);
            assertRow(sheet, 5, "Всего", 1000, 100, 8, 100, null);
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("Время проверки");
            assertThat(sheet.getRow(6).getCell(1).getNumericCellValue()).isEqualTo(700);

            // Под таблицей - пороги, с которыми построен отчет
            assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("Красный: доля времени от 50.0 %");
            assertThat(color(sheet.getRow(10).getCell(0))).isEqualTo(YELLOW);
            assertThat(sheet.getRow(12).getCell(0).getStringCellValue()).startsWith("Зеленый в колонках замечаний");
        }
    }

    @Test
    void sharesAreZeroWhenNothingIsFound(@TempDir Path dir) throws IOException {
        ScanResult result = new ScanResult(dir, 1, 1, 0, List.of(), 0, false, List.of(), 0,
                List.of(new RuleTiming("jr:1", "OnlyRule", 0)), 0);
        Path file = dir.resolve("statistic.xlsx");

        new StatisticReportPrinter(file, DurationUnit.MILLISECONDS, TimingThresholds.defaults()).print(result);

        try (InputStream input = Files.newInputStream(file); XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Row row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(2).getNumericCellValue()).isZero();
            assertThat(row.getCell(3).getNumericCellValue()).isZero();
            assertThat(row.getCell(4).getNumericCellValue()).isZero();
        }
    }

    @Test
    void timeIsWrittenInChosenUnit(@TempDir Path dir) throws IOException {
        ScanResult result = new ScanResult(dir, 1, 1, 0, List.of(), 0, false, List.of(), 0,
                List.of(new RuleTiming("jr:1", "OnlyRule", 1500 * MILLI)), 1500 * MILLI);
        Path file = dir.resolve("statistic.xlsx");

        new StatisticReportPrinter(file, DurationUnit.SECONDS, TimingThresholds.defaults()).print(result);

        try (InputStream input = Files.newInputStream(file); XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Время работы, с");
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(1.5);
            assertThat(sheet.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("0.000");
        }
    }

    @Test
    void scannerMeasuresEveryRuleAndTheWholeCheck(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Sample.java"), "class Sample {}");
        Scanner scanner = new Scanner(
                new FileSystemSourceLoader(),
                new FileSystemConfigLoader(),
                new FileSystemTextFileLoader(),
                List.of(new TransactionOnPrivateMethodRule(), new AutowiredOnStaticFieldRule()),
                (number, total, rule) -> {},
                RuleSettings.empty());

        ScanResult result = scanner.scan(dir);

        assertThat(result.timings()).extracting(RuleTiming::code).containsExactly("jr:1", "jr:4");
        // Проверка в один поток не короче, чем все ее правила вместе
        assertThat(result.checkNanos())
                .isGreaterThanOrEqualTo(result.timings().stream().mapToLong(RuleTiming::nanos).sum());
    }

    private Violation violation(ErrorLevel level, String code, String name, int line) {
        return new Violation(level, ErrorType.CODE_SMELL, code, name, Path.of("Sample.java"), line, "замечание");
    }

    private void assertRow(
            Sheet sheet, int rowIndex, String rule, double time, double timeShare, double count, double countShare,
            String color) {
        Row row = sheet.getRow(rowIndex);
        assertThat(row.getCell(0).getStringCellValue()).isEqualTo(rule);
        assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(time);
        assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(timeShare);
        assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(count);
        assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(countShare);
        // Правило и время закрашены по доле времени, а число замечаний и их доля - зеленым, если что-то найдено
        String countColor = count > 0 && !TOTAL.equals(rule) ? GREEN : null;
        for (Cell cell : row) {
            assertThat(color(cell)).isEqualTo(cell.getColumnIndex() < TIME_COLUMNS ? color : countColor);
        }
    }

    // Цвет заливки ячейки в виде ARGB либо null, если заливки нет
    private String color(Cell cell) {
        XSSFCellStyle style = (XSSFCellStyle) cell.getCellStyle();
        if (style.getFillPattern() != FillPatternType.SOLID_FOREGROUND) {
            return null;
        }
        return style.getFillForegroundXSSFColor().getARGBHex();
    }

    private List<String> texts(Row row) {
        List<String> texts = new ArrayList<>();
        row.forEach(cell -> texts.add(cell.getStringCellValue()));
        return texts;
    }
}
