package ru.akvine.zond.printers;

import lombok.RequiredArgsConstructor;
import ru.akvine.zond.models.ScanResult;

@RequiredArgsConstructor
public class ConsolePrinter implements Printer {
    private final ReportFormatter formatter;

    @Override
    public void print(ScanResult result) {
        System.out.print(formatter.format(result));
    }
}
