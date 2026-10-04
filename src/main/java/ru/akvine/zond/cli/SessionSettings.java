package ru.akvine.zond.cli;

import lombok.Getter;
import lombok.Setter;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.FileKind;
import ru.akvine.zond.enums.ReportFormat;
import ru.akvine.zond.models.PathExclusions;
import ru.akvine.zond.models.ScanOptions;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Настройки текущего запуска: читаются из app.properties и аргументов, меняются из меню
 */
@Getter
@Setter
public class SessionSettings {
    private static final String DEFAULT_REPORT_NAME = "zond-report";
    private static final String RULES_SEPARATOR = "[,;\\s]+";
    private static final String RULES_DELIMITER = ", ";

    /**
     * Куда писать отчет; null - текущая папка
     */
    private Path reportDirectory;

    /**
     * Имя файла отчета вместе с расширением; null - отчет выводится в консоль
     */
    private String reportFileName;

    private boolean skipTests;
    private ErrorLevel minLevel = ErrorLevel.INFO;

    /**
     * Коды и имена отключенных правил в том виде, как их задал пользователь
     */
    private final Set<String> disabledRules = new LinkedHashSet<>();

    /**
     * Библиотеки проверяемого проекта, по которым разрешаются типы
     */
    private List<Path> classpath = List.of();

    /**
     * Пути, которые не нужно сканировать
     */
    private PathExclusions exclusions = PathExclusions.none();

    /**
     * Сколько потоков использовать при сканировании: 1 - один, 0 - по числу ядер процессора
     */
    private int threads = 1;

    /**
     * Виды файлов помимо Java, которые проверять не нужно
     */
    private final Set<FileKind> skippedKinds = EnumSet.noneOf(FileKind.class);

    /**
     * @param reportFile    файл отчета либо null для вывода в консоль
     * @param disabledRules правила через запятую
     * @param minLevel      имя уровня либо пустая строка
     * @throws IllegalArgumentException если уровень задан неверно
     */
    public static SessionSettings of(Path reportFile, String disabledRules, String minLevel, boolean skipTests) {
        SessionSettings settings = new SessionSettings();
        if (reportFile != null) {
            settings.reportDirectory = reportFile.getParent();
            settings.reportFileName = reportFile.getFileName().toString();
        }
        Arrays.stream(disabledRules.split(RULES_SEPARATOR))
                .filter(rule -> !rule.isBlank())
                .forEach(settings.disabledRules::add);
        settings.minLevel = ScanOptions.parse("", minLevel).minLevel();
        settings.skipTests = skipTests;
        return settings;
    }

    /**
     * @return файл отчета либо null, если отчет выводится в консоль
     */
    public Path reportFile() {
        if (reportFileName == null) {
            return null;
        }
        return reportDirectory == null ? Path.of(reportFileName) : reportDirectory.resolve(reportFileName);
    }

    // Формат определяется расширением файла, как и при записи отчета
    public ReportFormat reportFormat() {
        if (reportFileName == null) {
            return ReportFormat.CONSOLE;
        }
        return ReportFormat.of(reportFileName);
    }

    // Имя файла сохраняется, меняется только расширение
    public void setReportFormat(ReportFormat format) {
        if (format == ReportFormat.CONSOLE) {
            reportFileName = null;
            return;
        }
        String current = reportFileName == null ? DEFAULT_REPORT_NAME : reportFileName;
        int dot = current.lastIndexOf('.');
        reportFileName = (dot > 0 ? current.substring(0, dot) : current) + format.getExtension();
    }

    public boolean isScanned(FileKind kind) {
        return !skippedKinds.contains(kind);
    }

    public void setScanned(FileKind kind, boolean scanned) {
        if (scanned) {
            skippedKinds.remove(kind);
        } else {
            skippedKinds.add(kind);
        }
    }

    public String disabledRulesAsText() {
        return String.join(RULES_DELIMITER, disabledRules);
    }

    public ScanOptions toScanOptions() {
        return ScanOptions.parse(disabledRulesAsText(), minLevel.name())
                .withSkipTests(skipTests)
                .withClasspath(classpath)
                .withExclusions(exclusions)
                .withThreads(threads)
                .withSkippedKinds(skippedKinds);
    }
}
