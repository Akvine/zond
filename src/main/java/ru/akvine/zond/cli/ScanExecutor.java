package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.akvine.zond.loaders.MavenClasspathResolver;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.printers.PrinterFactory;
import ru.akvine.zond.services.Scanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Запускает сканирование с настройками сеанса и выводит результат туда, куда они указывают
 */
@Component
@RequiredArgsConstructor
public class ScanExecutor {
    public static final int EXIT_OK = 0;
    public static final int EXIT_VIOLATIONS_FOUND = 1;
    public static final int EXIT_ERROR = 2;
    private static final int SHOWN_MISSING = 5;
    private static final String TOTAL_TIME = "Затраченное время: ";

    private final Scanner scanner;
    private final PrinterFactory printerFactory;
    private final MavenClasspathResolver classpathResolver;

    /**
     * @return код завершения: 0 - проблем нет, 1 - проблемы найдены, 2 - сканирование не удалось
     */
    public int scan(Path target, SessionSettings settings) {
        // В общее время входит все, чего ждет пользователь: поиск библиотек, разбор файлов, правила и запись отчета
        long startedAt = System.nanoTime();
        try {
            ScanResult result = scanner.scan(target, optionsOf(target, settings));
            printerFactory.create(settings.reportFile()).print(result);
            System.out.println(TOTAL_TIME + settings.getTimeUnit().format(System.nanoTime() - startedAt));
            return result.hasViolations() ? EXIT_VIOLATIONS_FOUND : EXIT_OK;
        } catch (RuntimeException exception) {
            System.err.println("Ошибка: " + exception.getMessage());
            return EXIT_ERROR;
        }
    }

    // К библиотекам из настроек добавляются найденные по pom.xml проекта: с ними правила узнают типы
    // из Spring, JPA и других зависимостей, а не судят о них по именам
    private ScanOptions optionsOf(Path target, SessionSettings settings) {
        ScanOptions options = settings.toScanOptions();
        if (!settings.isAutoClasspath()) {
            return options;
        }
        MavenClasspathResolver.Resolved resolved = classpathResolver.resolve(target);
        if (resolved.jars().isEmpty() && resolved.missing().isEmpty()) {
            return options;
        }
        System.out.println("Библиотеки проекта: найдено " + resolved.jars().size() + " в " + resolved.repository()
                + (resolved.missing().isEmpty() ? "" : "; не найдено " + resolved.missing().size() + ": "
                + String.join(", ", resolved.missing().subList(0, Math.min(SHOWN_MISSING, resolved.missing().size())))
                + (resolved.missing().size() > SHOWN_MISSING ? " и другие" : "")));
        List<Path> classpath = new ArrayList<>(options.classpath());
        classpath.addAll(resolved.jars());
        return options.withClasspath(classpath);
    }
}
