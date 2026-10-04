package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ReportFormat;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class PrinterFactory {
    private final ReportFormatter formatter;

    /**
     * Формат отчета определяется расширением файла: .xlsx - таблица Excel, .html - веб-страница,
     * .sarif - SARIF, любое другое - текст.
     *
     * @param reportFile файл отчета; если null - вывод в консоль
     */
    public Printer create(Path reportFile) {
        if (reportFile == null) {
            return new ConsolePrinter(formatter);
        }
        return switch (ReportFormat.of(reportFile.getFileName().toString())) {
            case XLSX -> new XlsxPrinter(reportFile);
            case HTML -> new HtmlPrinter(reportFile);
            case SARIF -> new SarifPrinter(reportFile);
            default -> new FilePrinter(formatter, reportFile);
        };
    }
}
