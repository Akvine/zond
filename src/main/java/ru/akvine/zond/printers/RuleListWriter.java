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
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ReportFormat;
import ru.akvine.zond.models.RuleInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Выгружает список правил в файл для ознакомления
 */
@Component
@RequiredArgsConstructor
public class RuleListWriter {
    private static final String SHEET = "Правила";
    private static final List<String> HEADERS =
            List.of("Код", "Уровень", "Тип", "Правило", "Описание", "Состояние", "Настройки");

    // Ширина колонок в символах, в порядке заголовков
    private static final List<Integer> COLUMN_WIDTHS = List.of(9, 12, 24, 52, 120, 14, 90);

    // POI задает ширину колонки в 1/256 ширины символа
    private static final int WIDTH_UNIT = 256;
    private static final int DESCRIPTION_COLUMN = 4;

    private static final String ACTIVE = "активно";
    private static final String DISABLED = "отключено";

    private final RuleListFormatter formatter;

    /**
     * Формат определяется расширением файла: .xlsx - таблица Excel, любое другое - текст.
     *
     * @throws UncheckedIOException если файл не удалось записать
     */
    public void write(Path file, List<RuleInfo> rules) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(ReportFormat.XLSX.getExtension())) {
                writeXlsx(file, rules);
            } else {
                Files.writeString(file, formatter.format(rules), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось записать список правил: " + file, exception);
        }
    }

    private void writeXlsx(Path file, List<RuleInfo> rules) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(SHEET);
            for (int column = 0; column < COLUMN_WIDTHS.size(); column++) {
                sheet.setColumnWidth(column, COLUMN_WIDTHS.get(column) * WIDTH_UNIT);
            }

            Row header = sheet.createRow(0);
            CellStyle headerStyle = headerStyle(workbook);
            for (int column = 0; column < HEADERS.size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(HEADERS.get(column));
                cell.setCellStyle(headerStyle);
            }

            // Шапка остается на месте при прокрутке, по любой колонке можно отфильтровать
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, rules.size(), 0, HEADERS.size() - 1));

            CellStyle wrapped = workbook.createCellStyle();
            wrapped.setWrapText(true);
            wrapped.setVerticalAlignment(VerticalAlignment.TOP);

            int row = 1;
            for (RuleInfo rule : rules) {
                Row line = sheet.createRow(row++);
                line.createCell(0).setCellValue(rule.code());
                line.createCell(1).setCellValue(rule.level().name());
                line.createCell(2).setCellValue(
                        rule.type().getDescription() == null ? rule.type().name() : rule.type().getDescription());
                line.createCell(3).setCellValue(rule.name());

                Cell description = line.createCell(DESCRIPTION_COLUMN);
                description.setCellValue(rule.description());
                description.setCellStyle(wrapped);

                line.createCell(5).setCellValue(rule.active() ? ACTIVE : DISABLED);

                Cell settings = line.createCell(6);
                settings.setCellValue(String.join(System.lineSeparator(), rule.settings()));
                settings.setCellStyle(wrapped);
            }

            try (OutputStream output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }
    }

    private CellStyle headerStyle(SXSSFWorkbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);

        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
