package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class PrinterFactory {
    private static final String XLSX_EXTENSION = ".xlsx";

    private final ReportFormatter formatter;

    /**
     * Формат отчета определяется расширением файла: .xlsx - таблица Excel, любое другое - текст.
     *
     * @param reportFile файл отчета; если null - вывод в консоль
     */
    public Printer create(Path reportFile) {
        if (reportFile == null) {
            return new ConsolePrinter(formatter);
        }

        String fileName = reportFile.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(XLSX_EXTENSION) ? new XlsxPrinter(reportFile) : new FilePrinter(formatter, reportFile);
    }
}
