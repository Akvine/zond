package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Отчет в формате Excel: лист со сводкой и лист со всеми проблемами, по которому можно фильтровать и сортировать
 */
@RequiredArgsConstructor
public class XlsxPrinter implements Printer {
    private static final String SUMMARY_SHEET = "Сводка";
    private static final String VIOLATIONS_SHEET = "Проблемы";
    private static final String FAILED_FILES_SHEET = "Не разобраны";

    private static final List<String> VIOLATION_HEADERS =
            List.of("Уровень", "Тип", "Код", "Правило", "Файл", "Строка", "Сообщение", "Уверенность");

    // Ширина колонок листа с проблемами в символах, в порядке заголовков
    private static final List<Integer> VIOLATION_COLUMN_WIDTHS = List.of(12, 24, 9, 46, 70, 9, 120, 16);
    private static final int SUMMARY_LABEL_WIDTH = 46;
    private static final int SUMMARY_VALUE_WIDTH = 60;

    // POI задает ширину колонки в 1/256 ширины символа
    private static final int WIDTH_UNIT = 256;

    // Сколько строк SXSSF держит в памяти, остальные сбрасывает на диск
    private static final int ROWS_IN_MEMORY = 200;

    private static final Map<ErrorLevel, IndexedColors> LEVEL_COLORS = new EnumMap<>(Map.of(
            ErrorLevel.BLOCKER, IndexedColors.RED,
            ErrorLevel.CRITICAL, IndexedColors.CORAL,
            ErrorLevel.MAJOR, IndexedColors.LIGHT_ORANGE,
            ErrorLevel.MINOR, IndexedColors.LIGHT_YELLOW));

    private final Path reportFile;
    // Показывать ли уверенность находок
    private final boolean showConfidence;

    public XlsxPrinter(Path reportFile) {
        this(reportFile, true);
    }

    @Override
    public void print(ScanResult result) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(ROWS_IN_MEMORY)) {
            Styles styles = new Styles(workbook);
            writeSummary(workbook.createSheet(SUMMARY_SHEET), result, styles);
            writeViolations(workbook.createSheet(VIOLATIONS_SHEET), result, styles);
            if (!result.failedFiles().isEmpty()) {
                writeFailedFiles(workbook.createSheet(FAILED_FILES_SHEET), result, styles);
            }

            Path parent = reportFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(reportFile)) {
                workbook.write(output);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось записать отчет: " + reportFile, exception);
        }
        System.out.println("Отчет записан: " + reportFile.toAbsolutePath().normalize()
                + " (проблем: " + result.violations().size() + ")");
    }

    private void writeSummary(Sheet sheet, ScanResult result, Styles styles) {
        sheet.setColumnWidth(0, SUMMARY_LABEL_WIDTH * WIDTH_UNIT);
        sheet.setColumnWidth(1, SUMMARY_VALUE_WIDTH * WIDTH_UNIT);
        sheet.setColumnWidth(2, VIOLATION_COLUMN_WIDTHS.get(0) * WIDTH_UNIT);

        int row = 0;
        row = writeHeader(sheet, row, styles, "Zond: отчет о сканировании");
        row = writePair(sheet, row, "Путь", result.root().toAbsolutePath().normalize().toString());
        row = writePair(sheet, row, "Файлов проверено", result.filesCount());
        row = writePair(sheet, row, "Каталоги test", result.testsSkipped() ? "пропущены" : "проверены");
        row = writePair(sheet, row, "Активных правил", result.rulesCount());
        row = writePair(sheet, row, "Правил отключено настройками", result.disabledRulesCount());
        row = writePair(sheet, row, "Найдено проблем", result.violations().size());
        row = writePair(sheet, row, "Скрыто комментариями zond:ignore", result.suppressedCount());
        row = writePair(sheet, row, "Файлов не удалось разобрать", result.failedFiles().size());

        // Все уровни, включая те, по которым ничего не найдено: так сразу видно, чего нет
        row = writeHeader(sheet, row + 1, styles, "По уровням");
        for (ErrorLevel level : ErrorLevel.values()) {
            long count = result.violations().stream().filter(violation -> violation.errorLevel() == level).count();
            Row line = sheet.createRow(row++);
            Cell label = line.createCell(0);
            label.setCellValue(level.name());
            label.setCellStyle(styles.forLevel(level));
            line.createCell(1).setCellValue(count);
        }

        row = writeHeader(sheet, row + 1, styles, "По правилам", "Проблем", "Код");
        for (Map.Entry<String, List<Violation>> rule : groupByRule(result).entrySet()) {
            Row line = sheet.createRow(row++);
            line.createCell(0).setCellValue(rule.getKey());
            line.createCell(1).setCellValue(rule.getValue().size());
            line.createCell(2).setCellValue(rule.getValue().get(0).ruleCode());
        }
    }

    private void writeViolations(Sheet sheet, ScanResult result, Styles styles) {
        // Уверенность - последняя колонка: без нее таблица просто на колонку короче
        List<String> headers = showConfidence
                ? VIOLATION_HEADERS
                : VIOLATION_HEADERS.subList(0, VIOLATION_HEADERS.size() - 1);
        for (int column = 0; column < headers.size(); column++) {
            sheet.setColumnWidth(column, VIOLATION_COLUMN_WIDTHS.get(column) * WIDTH_UNIT);
        }
        writeHeader(sheet, 0, styles, headers.toArray(String[]::new));

        // Шапка остается на месте при прокрутке, по любой колонке можно отфильтровать
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, result.violations().size(), 0, headers.size() - 1));

        int row = 1;
        for (Violation violation : result.violations()) {
            Row line = sheet.createRow(row++);
            Cell level = line.createCell(0);
            level.setCellValue(violation.errorLevel().name());
            level.setCellStyle(styles.forLevel(violation.errorLevel()));

            line.createCell(1).setCellValue(describe(violation.errorType()));
            line.createCell(2).setCellValue(violation.ruleCode());
            line.createCell(3).setCellValue(violation.ruleName());
            line.createCell(4).setCellValue(relativize(result.root(), violation.file()));
            line.createCell(5).setCellValue(violation.line());

            Cell message = line.createCell(6);
            message.setCellValue(violation.message());
            message.setCellStyle(styles.wrapped());
            if (showConfidence) {
                line.createCell(headers.size() - 1).setCellValue(violation.confidenceOrDefault().getTitle());
            }
        }
    }

    private void writeFailedFiles(Sheet sheet, ScanResult result, Styles styles) {
        sheet.setColumnWidth(0, VIOLATION_COLUMN_WIDTHS.get(6) * WIDTH_UNIT);
        int row = writeHeader(sheet, 0, styles, "Файл");
        for (Path file : result.failedFiles()) {
            sheet.createRow(row++).createCell(0).setCellValue(relativize(result.root(), file));
        }
    }

    private int writeHeader(Sheet sheet, int row, Styles styles, String... titles) {
        Row header = sheet.createRow(row);
        for (int column = 0; column < titles.length; column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(titles[column]);
            cell.setCellStyle(styles.header());
        }
        return row + 1;
    }

    private int writePair(Sheet sheet, int row, String label, String value) {
        Row line = sheet.createRow(row);
        line.createCell(0).setCellValue(label);
        line.createCell(1).setCellValue(value);
        return row + 1;
    }

    private int writePair(Sheet sheet, int row, String label, long value) {
        Row line = sheet.createRow(row);
        line.createCell(0).setCellValue(label);
        line.createCell(1).setCellValue(value);
        return row + 1;
    }

    // Правила с наибольшим числом проблем - первыми
    private Map<String, List<Violation>> groupByRule(ScanResult result) {
        Map<String, List<Violation>> byRule = new LinkedHashMap<>();
        for (Violation violation : result.violations()) {
            byRule.computeIfAbsent(violation.ruleName(), name -> new ArrayList<>()).add(violation);
        }

        Map<String, List<Violation>> sorted = new LinkedHashMap<>();
        byRule.entrySet().stream()
                .sorted(Map.Entry.<String, List<Violation>>comparingByValue(Comparator.comparingInt(List::size))
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private String describe(ErrorType type) {
        return type.getDescription() == null ? type.name() : type.getDescription();
    }

    // Путь от корня сканирования короче и не зависит от того, где лежит проект
    private String relativize(Path root, Path file) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        boolean inside = absoluteFile.startsWith(absoluteRoot) && !absoluteFile.equals(absoluteRoot);
        return inside ? absoluteRoot.relativize(absoluteFile).toString() : file.toString();
    }

    /**
     * Стили книги. Excel ограничивает их число, поэтому каждый создается один раз на весь документ
     */
    private static final class Styles {
        private final CellStyle header;
        private final CellStyle wrapped;
        private final CellStyle plain;
        private final Map<ErrorLevel, CellStyle> levels = new EnumMap<>(ErrorLevel.class);

        private Styles(Workbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);

            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            wrapped = workbook.createCellStyle();
            wrapped.setWrapText(true);
            wrapped.setVerticalAlignment(VerticalAlignment.TOP);

            plain = workbook.createCellStyle();

            LEVEL_COLORS.forEach((level, color) -> {
                CellStyle style = workbook.createCellStyle();
                style.setFillForegroundColor(color.getIndex());
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                levels.put(level, style);
            });
        }

        private CellStyle header() {
            return header;
        }

        private CellStyle wrapped() {
            return wrapped;
        }

        // Уровень без своего цвета (INFO) остается без заливки
        private CellStyle forLevel(ErrorLevel level) {
            return levels.getOrDefault(level, plain);
        }
    }
}
