package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.printers.PrinterFactory;
import ru.akvine.zond.services.Scanner;

import java.nio.file.Path;

/**
 * Запускает сканирование с настройками сеанса и выводит результат туда, куда они указывают
 */
@Component
@RequiredArgsConstructor
public class ScanExecutor {
    public static final int EXIT_OK = 0;
    public static final int EXIT_VIOLATIONS_FOUND = 1;
    public static final int EXIT_ERROR = 2;

    private final Scanner scanner;
    private final PrinterFactory printerFactory;

    /**
     * @return код завершения: 0 - проблем нет, 1 - проблемы найдены, 2 - сканирование не удалось
     */
    public int scan(Path target, SessionSettings settings) {
        try {
            ScanResult result = scanner.scan(target, settings.toScanOptions());
            printerFactory.create(settings.reportFile()).print(result);
            return result.hasViolations() ? EXIT_VIOLATIONS_FOUND : EXIT_OK;
        } catch (RuntimeException exception) {
            System.err.println("Ошибка: " + exception.getMessage());
            return EXIT_ERROR;
        }
    }
}
