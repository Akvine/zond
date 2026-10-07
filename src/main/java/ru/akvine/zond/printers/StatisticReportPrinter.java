package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.akvine.zond.config.ZondVersion;
import ru.akvine.zond.enums.DurationUnit;
import ru.akvine.zond.enums.TimingZone;
import ru.akvine.zond.models.RuleTiming;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.TimingThresholds;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Статистика по правилам в формате Excel: сколько каждое правило работало и сколько замечаний нашло,
 * вместе с долями в общем времени и в общем числе замечаний. Время закрашено по порогам из настроек,
 * самые медленные правила идут первыми.
 */
@RequiredArgsConstructor
public class StatisticReportPrinter implements Printer {
    public static final String EXTENSION = ".xlsx";

    private static final String SHEET = "Статистика правил";
    private static final String RULE_HEADER = "Правило";
    private static final String TIME_HEADER = "Время работы, ";
    private static final String TIME_SHARE_HEADER = "Доля от общего времени, %";
    private static final String COUNT_HEADER = "Найдено замечаний";
    private static final String COUNT_SHARE_HEADER = "Доля от всех замечаний, %";
    private static final String TOTAL = "Всего";
    private static final String CHECK_TIME = "Время проверки";
    private static final String LEGEND = "Цвета времени";
    private static final String FOUND_LEGEND = "Зеленый в колонках замечаний: правило что-то нашло";
    private static final String PERCENT_FORMAT = "0.00";
    private static final String COUNT_FORMAT = "0";
    private static final double FULL = 100.0;

    private static final int RULE_COLUMN = 0;
    private static final int TIME_COLUMN = 1;
    private static final int TIME_SHARE_COLUMN = 2;
    private static final int COUNT_COLUMN = 3;
    private static final int COUNT_SHARE_COLUMN = 4;

    private static final int RULE_WIDTH = 60;
    private static final int NUMBER_WIDTH = 28;
    // POI задает ширину колонки в 1/256 ширины символа
    private static final int WIDTH_UNIT = 256;

    // Мягкие заливки, как у условного форматирования Excel: текст на них остается читаемым
    private static final Map<TimingZone, byte[]> COLORS = new EnumMap<>(Map.of(
            TimingZone.RED, new byte[] {(byte) 0xFF, (byte) 0xC7, (byte) 0xCE},
            TimingZone.YELLOW, new byte[] {(byte) 0xFF, (byte) 0xEB, (byte) 0x9C},
            TimingZone.GREEN, new byte[] {(byte) 0xC6, (byte) 0xEF, (byte) 0xCE}));

    private final Path reportFile;
    private final DurationUnit unit;
    private final TimingThresholds thresholds;

    @Override
    public void print(ScanResult result) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            write(workbook.createSheet(SHEET), result, new Styles(workbook, unit));

            Path parent = reportFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(reportFile)) {
                workbook.write(output);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось записать статистику по правилам: " + reportFile, exception);
        }
        System.out.println("Статистика по правилам записана: " + reportFile.toAbsolutePath().normalize());
    }

    private void write(Sheet sheet, ScanResult result, Styles styles) {
        List<String> headers = List.of(
                RULE_HEADER, TIME_HEADER + unit.getSign(), TIME_SHARE_HEADER, COUNT_HEADER, COUNT_SHARE_HEADER);
        Row header = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) {
            sheet.setColumnWidth(column, (column == RULE_COLUMN ? RULE_WIDTH : NUMBER_WIDTH) * WIDTH_UNIT);
            Cell cell = header.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(styles.header);
        }
        sheet.createFreezePane(0, 1);

        // Доля времени считается от суммы времени всех правил: так доли в сумме всегда дают 100 %, в том числе
        // при работе в несколько потоков, где правила идут одновременно
        long totalNanos = result.timings().stream().mapToLong(RuleTiming::nanos).sum();
        // Замечания считаются все вместе, без деления по уровням: в отчет попали - значит, найдены
        Map<String, Long> found = result.violations().stream()
                .collect(Collectors.groupingBy(Violation::ruleCode, Collectors.counting()));
        long totalFound = result.violations().size();

        List<RuleTiming> slowestFirst = result.timings().stream()
                .sorted(Comparator.comparingLong(RuleTiming::nanos).reversed().thenComparing(RuleTiming::name))
                .toList();

        int rowIndex = 1;
        for (RuleTiming timing : slowestFirst) {
            double timeShare = share(timing.nanos(), totalNanos);
            long count = found.getOrDefault(timing.code(), 0L);
            // Правила, которые что-то нашли, выделены зеленым: их видно среди сотен строк с нулем
            writeRow(sheet.createRow(rowIndex++), timing.name() + " (" + timing.code() + ")",
                    timing.nanos(), timeShare, count, totalFound,
                    styles.of(thresholds.zoneOf(timeShare)), count > 0 ? styles.of(TimingZone.GREEN) : styles.plain);
        }
        if (rowIndex > 1) {
            sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, headers.size() - 1));
        }
        writeRow(sheet.createRow(rowIndex++), TOTAL, totalNanos, share(totalNanos, totalNanos), totalFound, totalFound,
                styles.total, styles.total);

        // Для сравнения: сколько проверка заняла по часам, от запуска первого правила до конца последнего
        Row check = sheet.createRow(rowIndex++);
        check.createCell(RULE_COLUMN).setCellValue(CHECK_TIME);
        Cell checkTime = check.createCell(TIME_COLUMN);
        checkTime.setCellValue(unit.value(result.checkNanos()));
        checkTime.setCellStyle(styles.plain.time());

        writeLegend(sheet, rowIndex + 1, styles);
    }

    /**
     * @param timeStyles  стили правила и его времени: закрашены по доле времени
     * @param countStyles стили числа замечаний и их доли: цвет времени к ним не относится
     */
    private void writeRow(
            Row row, String title, long nanos, double timeShare, long count, long totalCount,
            RowStyles timeStyles, RowStyles countStyles) {
        Cell rule = row.createCell(RULE_COLUMN);
        rule.setCellValue(title);
        rule.setCellStyle(timeStyles.text);

        number(row, TIME_COLUMN, unit.value(nanos), timeStyles.time);
        number(row, TIME_SHARE_COLUMN, timeShare, timeStyles.percent);
        number(row, COUNT_COLUMN, count, countStyles.count);
        number(row, COUNT_SHARE_COLUMN, share(count, totalCount), countStyles.percent);
    }

    private void number(Row row, int column, double value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private double share(long part, long total) {
        return total == 0 ? 0 : part * FULL / total;
    }

    // По отчету должно быть видно, с какими порогами он построен и какой версией
    private void writeLegend(Sheet sheet, int firstRow, Styles styles) {
        int rowIndex = firstRow;
        Cell title = sheet.createRow(rowIndex++).createCell(RULE_COLUMN);
        title.setCellValue(LEGEND);
        title.setCellStyle(styles.header);

        for (TimingZone zone : List.of(TimingZone.RED, TimingZone.YELLOW, TimingZone.GREEN)) {
            Cell cell = sheet.createRow(rowIndex++).createCell(RULE_COLUMN);
            cell.setCellValue(zone.getTitle() + ": доля времени от " + thresholds.of(zone) + " %");
            cell.setCellStyle(styles.of(zone).text);
        }
        Cell found = sheet.createRow(rowIndex++).createCell(RULE_COLUMN);
        found.setCellValue(FOUND_LEGEND);
        found.setCellStyle(styles.of(TimingZone.GREEN).text);
        sheet.createRow(rowIndex).createCell(RULE_COLUMN).setCellValue(ZondVersion.title());
    }

    /**
     * Стили одной строки: числовой формат у колонок разный, а заливка общая
     */
    private record RowStyles(CellStyle text, CellStyle time, CellStyle percent, CellStyle count) {
    }

    private static final class Styles {
        private final CellStyle header;
        private final RowStyles total;
        private final RowStyles plain;
        private final Map<TimingZone, RowStyles> zones = new EnumMap<>(TimingZone.class);

        private Styles(XSSFWorkbook workbook, DurationUnit unit) {
            Font bold = workbook.createFont();
            bold.setBold(true);

            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            short timeFormat = workbook.createDataFormat().getFormat(unit.getCellFormat());
            short percentFormat = workbook.createDataFormat().getFormat(PERCENT_FORMAT);
            short countFormat = workbook.createDataFormat().getFormat(COUNT_FORMAT);
            for (TimingZone zone : TimingZone.values()) {
                zones.put(zone, row(workbook, COLORS.get(zone), null, timeFormat, percentFormat, countFormat));
            }
            total = row(workbook, null, bold, timeFormat, percentFormat, countFormat);
            plain = zones.get(TimingZone.NONE);
        }

        private RowStyles of(TimingZone zone) {
            return zones.get(zone);
        }

        private static RowStyles row(
                XSSFWorkbook workbook, byte[] color, Font font, short timeFormat, short percentFormat, short countFormat) {
            return new RowStyles(
                    style(workbook, color, font, null),
                    style(workbook, color, font, timeFormat),
                    style(workbook, color, font, percentFormat),
                    style(workbook, color, font, countFormat));
        }

        private static CellStyle style(XSSFWorkbook workbook, byte[] color, Font font, Short format) {
            XSSFCellStyle style = workbook.createCellStyle();
            if (color != null) {
                style.setFillForegroundColor(new XSSFColor(color, null));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (font != null) {
                style.setFont(font);
            }
            if (format != null) {
                style.setDataFormat(format);
            }
            return style;
        }
    }
}
