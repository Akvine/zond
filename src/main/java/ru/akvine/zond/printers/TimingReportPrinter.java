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

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Отчет по времени работы правил в формате Excel: правило, время и доля в общем времени всех правил.
 * Строки закрашены по порогам из настроек, самые медленные правила идут первыми.
 */
@RequiredArgsConstructor
public class TimingReportPrinter implements Printer {
    public static final String EXTENSION = ".xlsx";

    private static final String SHEET = "Время правил";
    private static final String RULE_HEADER = "Правило";
    private static final String TIME_HEADER = "Время работы, ";
    private static final String PERCENT_HEADER = "Доля от общего времени, %";
    private static final String TOTAL = "Всего";
    private static final String CHECK_TIME = "Время проверки";
    private static final String LEGEND = "Цвета";
    private static final String PERCENT_FORMAT = "0.00";
    private static final double FULL = 100.0;

    private static final int RULE_WIDTH = 60;
    private static final int NUMBER_WIDTH = 28;
    // POI задает ширину колонки в 1/256 ширины символа
    private static final int WIDTH_UNIT = 256;
    private static final int COLUMNS = 3;

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
            throw new UncheckedIOException("Не удалось записать отчет по времени правил: " + reportFile, exception);
        }
        System.out.println("Отчет по времени правил записан: " + reportFile.toAbsolutePath().normalize());
    }

    private void write(Sheet sheet, ScanResult result, Styles styles) {
        sheet.setColumnWidth(0, RULE_WIDTH * WIDTH_UNIT);
        sheet.setColumnWidth(1, NUMBER_WIDTH * WIDTH_UNIT);
        sheet.setColumnWidth(2, NUMBER_WIDTH * WIDTH_UNIT);

        Row header = sheet.createRow(0);
        List<String> headers = List.of(RULE_HEADER, TIME_HEADER + unit.getSign(), PERCENT_HEADER);
        for (int column = 0; column < COLUMNS; column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(styles.header);
        }
        sheet.createFreezePane(0, 1);

        // Доля считается от суммы времени всех правил: так доли в сумме всегда дают 100 %, в том числе
        // при работе в несколько потоков, где правила идут одновременно
        long total = result.timings().stream().mapToLong(RuleTiming::nanos).sum();
        List<RuleTiming> slowestFirst = result.timings().stream()
                .sorted(Comparator.comparingLong(RuleTiming::nanos).reversed().thenComparing(RuleTiming::name))
                .toList();

        int rowIndex = 1;
        for (RuleTiming timing : slowestFirst) {
            double percent = total == 0 ? 0 : timing.nanos() * FULL / total;
            writeRow(sheet.createRow(rowIndex++), timing.name() + " (" + timing.code() + ")", timing.nanos(), percent,
                    styles.of(thresholds.zoneOf(percent)));
        }
        if (rowIndex > 1) {
            sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, COLUMNS - 1));
        }
        writeRow(sheet.createRow(rowIndex++), TOTAL, total, total == 0 ? 0 : FULL, styles.total);

        // Для сравнения: сколько проверка заняла по часам, от запуска первого правила до конца последнего
        Row check = sheet.createRow(rowIndex++);
        check.createCell(0).setCellValue(CHECK_TIME);
        Cell checkTime = check.createCell(1);
        checkTime.setCellValue(unit.value(result.checkNanos()));
        checkTime.setCellStyle(styles.of(TimingZone.NONE).time());

        writeLegend(sheet, rowIndex + 1, styles);
    }

    private void writeRow(Row row, String title, long nanos, double percent, RowStyles styles) {
        Cell rule = row.createCell(0);
        rule.setCellValue(title);
        rule.setCellStyle(styles.text);

        Cell time = row.createCell(1);
        time.setCellValue(unit.value(nanos));
        time.setCellStyle(styles.time);

        Cell share = row.createCell(2);
        share.setCellValue(percent);
        share.setCellStyle(styles.percent);
    }

    // По отчету должно быть видно, с какими порогами он построен и какой версией
    private void writeLegend(Sheet sheet, int firstRow, Styles styles) {
        int rowIndex = firstRow;
        Cell title = sheet.createRow(rowIndex++).createCell(0);
        title.setCellValue(LEGEND);
        title.setCellStyle(styles.header);

        for (TimingZone zone : List.of(TimingZone.RED, TimingZone.YELLOW, TimingZone.GREEN)) {
            Cell cell = sheet.createRow(rowIndex++).createCell(0);
            cell.setCellValue(zone.getTitle() + ": доля от " + thresholds.of(zone) + " %");
            cell.setCellStyle(styles.of(zone).text);
        }
        sheet.createRow(rowIndex).createCell(0).setCellValue(ZondVersion.title());
    }

    /**
     * Стили одной строки: у текста, времени и доли разный числовой формат, а заливка общая
     */
    private record RowStyles(CellStyle text, CellStyle time, CellStyle percent) {
    }

    private static final class Styles {
        private final CellStyle header;
        private final RowStyles total;
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
            for (TimingZone zone : TimingZone.values()) {
                zones.put(zone, new RowStyles(
                        style(workbook, COLORS.get(zone), null, null),
                        style(workbook, COLORS.get(zone), null, timeFormat),
                        style(workbook, COLORS.get(zone), null, percentFormat)));
            }
            total = new RowStyles(
                    style(workbook, null, bold, null),
                    style(workbook, null, bold, timeFormat),
                    style(workbook, null, bold, percentFormat));
        }

        private RowStyles of(TimingZone zone) {
            return zones.get(zone);
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
