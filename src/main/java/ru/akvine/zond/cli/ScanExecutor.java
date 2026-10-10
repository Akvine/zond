package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.akvine.zond.loaders.GitChangedFiles;
import ru.akvine.zond.loaders.HashChangedFiles;
import ru.akvine.zond.loaders.MavenClasspathResolver;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.printers.PrinterFactory;
import ru.akvine.zond.printers.StatisticReportPrinter;
import ru.akvine.zond.services.Scanner;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private static final DateTimeFormatter CHECK_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final Scanner scanner;
    private final PrinterFactory printerFactory;
    private final MavenClasspathResolver classpathResolver;
    private final GitChangedFiles gitChanges;
    private final HashChangedFiles hashChanges;

    /**
     * Что считать измененным в этом запуске
     *
     * @param files    измененные файлы; null - проверять весь проект
     * @param snapshot хеши файлов, которые нужно запомнить после проверки; null - изменения дал git
     */
    private record Changed(Set<Path> files, HashChangedFiles.Snapshot snapshot) {
        private static final Changed WHOLE_PROJECT = new Changed(null, null);

        private boolean isNothing() {
            return files != null && files.isEmpty();
        }
    }

    /**
     * @return код завершения: 0 - проблем нет, 1 - проблемы найдены, 2 - сканирование не удалось
     */
    public int scan(Path target, SessionSettings settings) {
        try {
            Changed changed = changedFilesOf(target, settings);
            // Изменений нет - проверять нечего: проект даже не читаем, но отчет пишем, чтобы его было видно
            ScanResult result = changed.isNothing()
                    ? new ScanResult(target, 0, 0, 0, List.of(), 0, settings.isSkipTests(), List.of(), 0, List.of(), 0, 0)
                    : scanner.scan(target, optionsOf(target, settings).withChangedFiles(changed.files()));
            printerFactory.create(settings.reportFile()).print(result);
            // Точка отсчета сдвигается, только когда отчет уже записан: иначе находки этого запуска пропали бы
            remember(changed);
            // Время считается от запуска первого правила: загрузка и разбор кода в него не входят
            if (settings.getStatisticReportFile() != null) {
                new StatisticReportPrinter(
                        settings.getStatisticReportFile(), settings.getTimeUnit(), settings.getTimingThresholds())
                        .print(result);
            }
            System.out.println(TOTAL_TIME + settings.getTimeUnit().format(result.checkNanos()));
            return result.hasViolations() ? EXIT_VIOLATIONS_FOUND : EXIT_OK;
        } catch (RuntimeException exception) {
            System.err.println("Ошибка: " + exception.getMessage());
            return EXIT_ERROR;
        }
    }

    /**
     * Измененные файлы, если просили проверять только их. В репозитории git их называет git, в остальных
     * папках они находятся сравнением хешей с теми, что запомнены после прошлой проверки
     */
    private Changed changedFilesOf(Path target, SessionSettings settings) {
        if (!settings.isChangedOnly()) {
            return Changed.WHOLE_PROJECT;
        }
        Optional<GitChangedFiles.Changes> byGit = gitChanges.find(target, settings.getChangedSince());
        if (byGit.isPresent()) {
            System.out.println("Измененных файлов: " + byGit.get().files().size()
                    + " (сравнение с " + byGit.get().base() + ")");
            return new Changed(byGit.get().files(), null);
        }

        ScanOptions options = settings.toScanOptions();
        HashChangedFiles.Changes byHash = hashChanges.find(target, file -> options.includes(target, file));
        if (!settings.getChangedSince().isEmpty()) {
            System.out.println("Точка отсчета '" + settings.getChangedSince() + "' не используется: она имеет смысл"
                    + " только в репозитории git");
        }
        if (byHash.previous() == null) {
            System.out.println("Папка не лежит в репозитории git либо git не установлен: измененные файлы ищутся"
                    + " по хешам. Прошлой проверки еще не было, поэтому проверяются все файлы");
            return new Changed(null, byHash.current());
        }
        System.out.println("Измененных файлов: " + byHash.files().size()
                + " (сравнение по хешам файлов с проверкой от " + CHECK_TIME.format(byHash.previous()) + ")");
        return new Changed(byHash.files(), byHash.current());
    }

    // Не запомнили хеши - следующая проверка просто пройдет по тем же файлам еще раз: это не повод считать
    // неудачной нынешнюю
    private void remember(Changed changed) {
        if (changed.snapshot() == null) {
            return;
        }
        try {
            Path file = hashChanges.remember(changed.snapshot());
            if (changed.files() == null) {
                System.out.println("Хеши файлов сохранены в " + file + ": следующая проверка покажет находки"
                        + " только в том, что с этого момента изменится");
            }
        } catch (UncheckedIOException exception) {
            System.err.println(exception.getMessage() + ": " + exception.getCause().getMessage());
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
