package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class PrinterFactory {
    private final ReportFormatter formatter;

    /**
     * @param reportFile файл отчета; если null - вывод в консоль
     */
    public Printer create(Path reportFile) {
        return reportFile == null ? new ConsolePrinter(formatter) : new FilePrinter(formatter, reportFile);
    }
}
