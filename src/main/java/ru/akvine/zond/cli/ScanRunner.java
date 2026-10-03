package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.printers.PrinterFactory;
import ru.akvine.zond.services.Scanner;

import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ScanRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final String PATH_OPTION = "path";
    private static final String REPORT_OPTION = "report";
    private static final String EXIT_COMMAND = "exit";

    private static final int EXIT_OK = 0;
    private static final int EXIT_VIOLATIONS_FOUND = 1;
    private static final int EXIT_ERROR = 2;

    private final Scanner scanner;
    private final PrinterFactory printerFactory;
    private final ZondSettings settings;
    private final ConsoleInput consoleInput;

    private int exitCode = EXIT_OK;

    @Override
    public void run(ApplicationArguments args) {
        Path report = resolveReport(args);
        String path = resolvePath(args);
        if (path != null) {
            scan(path, report);
            return;
        }
        runInteractive(report);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    // Путь не передан аргументом - спрашиваем у пользователя, пока он не выйдет
    private void runInteractive(Path report) {
        System.out.println("Введите путь к .java файлу или директории ('" + EXIT_COMMAND
                + "' или пустая строка - выход)");
        while (true) {
            String line = consoleInput.readLine("> ");
            if (line == null) {
                return;
            }

            String path = unquote(line.trim());
            if (path.isEmpty() || EXIT_COMMAND.equalsIgnoreCase(path)) {
                return;
            }
            scan(path, report);
        }
    }

    private void scan(String path, Path report) {
        try {
            ScanResult result = scanner.scan(Path.of(path));
            printerFactory.create(report).print(result);
            exitCode = result.hasViolations() ? EXIT_VIOLATIONS_FOUND : EXIT_OK;
        } catch (RuntimeException exception) {
            System.err.println("Ошибка: " + exception.getMessage());
            exitCode = EXIT_ERROR;
        }
    }

    // Проводник Windows при "Копировать как путь" оборачивает путь в кавычки
    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    // --path=... либо первый позиционный аргумент
    private String resolvePath(ApplicationArguments args) {
        String path = optionValue(args, PATH_OPTION);
        if (path != null) {
            return path;
        }
        return args.getNonOptionArgs().isEmpty() ? null : args.getNonOptionArgs().get(0);
    }

    // --report=... важнее, чем zond.report.path из app.properties
    private Path resolveReport(ApplicationArguments args) {
        String report = optionValue(args, REPORT_OPTION);
        return report == null ? settings.reportPath() : Path.of(report);
    }

    private String optionValue(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
