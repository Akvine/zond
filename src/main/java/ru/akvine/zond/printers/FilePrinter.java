package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import ru.akvine.zond.models.ScanResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RequiredArgsConstructor
public class FilePrinter implements Printer {
    private final ReportFormatter formatter;
    private final Path reportFile;

    @Override
    public void print(ScanResult result) {
        try {
            Path parent = reportFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(reportFile, formatter.format(result), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось записать отчет: " + reportFile, exception);
        }
        System.out.println("Отчет записан: " + reportFile.toAbsolutePath().normalize()
                + " (проблем: " + result.violations().size() + ")");
    }
}
