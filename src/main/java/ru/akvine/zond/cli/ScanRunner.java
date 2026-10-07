package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.DurationUnit;
import ru.akvine.zond.printers.TimingReportPrinter;
import ru.akvine.zond.enums.FileKind;
import ru.akvine.zond.models.PathExclusions;
import ru.akvine.zond.services.RuleCatalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ScanRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final String PATH_OPTION = "path";
    private static final String REPORT_OPTION = "report";
    private static final String DISABLE_OPTION = "disable";
    private static final String MIN_LEVEL_OPTION = "min-level";
    private static final String SKIP_TESTS_OPTION = "skip-tests";
    private static final String CLASSPATH_OPTION = "classpath";
    private static final String EXCLUDE_OPTION = "exclude";
    private static final String THREADS_OPTION = "threads";
    private static final String AUTO_CLASSPATH_OPTION = "auto-classpath";
    private static final String MIN_CONFIDENCE_OPTION = "min-confidence";
    private static final String TIME_UNIT_OPTION = "time-unit";
    private static final String TIMING_REPORT_OPTION = "timing-report";
    private static final String RULES_SEPARATOR = ",";
    private static final String TRUE = "true";
    private static final String FALSE = "false";

    private final ScanExecutor scanExecutor;
    private final MainMenu mainMenu;
    private final ZondSettings settings;
    private final RuleCatalog ruleCatalog;

    private int exitCode = ScanExecutor.EXIT_OK;

    @Override
    public void run(ApplicationArguments args) {
        // Ошибка в настройках: сканировать с неверным порогом хуже, чем не сканировать вовсе
        List<String> problems = ruleCatalog.findSettingsProblems();
        if (!problems.isEmpty()) {
            problems.forEach(problem -> System.err.println("Ошибка: " + problem));
            exitCode = ScanExecutor.EXIT_ERROR;
            return;
        }

        SessionSettings session;
        try {
            session = resolveSettings(args);
        } catch (IllegalArgumentException exception) {
            System.err.println("Ошибка: " + exception.getMessage());
            exitCode = ScanExecutor.EXIT_ERROR;
            return;
        }

        // Путь передан аргументом - сканируем и выходим, без меню: так приложение запускают скрипты и CI
        String path = resolvePath(args);
        exitCode = path != null ? scanExecutor.scan(Path.of(path), session) : mainMenu.open(session);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    // --path=... либо первый позиционный аргумент
    private String resolvePath(ApplicationArguments args) {
        String path = optionValue(args, PATH_OPTION);
        if (path != null) {
            return path;
        }
        return args.getNonOptionArgs().isEmpty() ? null : args.getNonOptionArgs().get(0);
    }

    // Аргументы важнее app.properties; правила из --disable=... добавляются к отключенным в файле
    private SessionSettings resolveSettings(ApplicationArguments args) {
        String report = optionValue(args, REPORT_OPTION);
        String disabledByArgument = optionValue(args, DISABLE_OPTION);
        String disabled = disabledByArgument == null
                ? settings.disabledRules()
                : settings.disabledRules() + RULES_SEPARATOR + disabledByArgument;
        String minLevel = optionValue(args, MIN_LEVEL_OPTION);

        SessionSettings session = SessionSettings.of(
                report == null ? settings.reportPath() : Path.of(report),
                disabled,
                minLevel == null ? settings.minLevel() : minLevel,
                resolveSkipTests(args));

        // --classpath=... заменяет библиотеки из zond.scan.classpath
        String classpath = optionValue(args, CLASSPATH_OPTION);
        session.setClasspath(classpath == null ? settings.classpath() : ZondSettings.parseClasspath(classpath));

        // Шаблоны из --exclude=... добавляются к заданным в zond.scan.exclude
        String excluded = optionValue(args, EXCLUDE_OPTION);
        session.setExclusions(PathExclusions.parse(
                excluded == null ? settings.exclude() : settings.exclude() + RULES_SEPARATOR + excluded));

        // --auto-classpath и --auto-classpath=true включают поиск библиотек, --auto-classpath=false отключает
        if (args.containsOption(AUTO_CLASSPATH_OPTION)) {
            String auto = optionValue(args, AUTO_CLASSPATH_OPTION);
            session.setAutoClasspath(auto == null || auto.isBlank() || Boolean.parseBoolean(auto.trim()));
        } else {
            session.setAutoClasspath(settings.autoClasspath());
        }

        String threads = optionValue(args, THREADS_OPTION);
        session.setThreads(parseThreads(threads == null ? settings.threads() : threads));

        for (FileKind kind : FileKind.values()) {
            session.setScanned(kind, resolveScanned(args, kind));
        }

        String minConfidence = optionValue(args, MIN_CONFIDENCE_OPTION);
        session.setMinConfidence(Confidence.parse(minConfidence == null ? settings.minConfidence() : minConfidence));

        String timeUnit = optionValue(args, TIME_UNIT_OPTION);
        session.setTimeUnit(DurationUnit.parse(timeUnit == null ? settings.timeUnit() : timeUnit));

        String timingReport = optionValue(args, TIMING_REPORT_OPTION);
        session.setTimingReportFile(parseTimingReport(timingReport == null ? settings.timingReportPath() : timingReport));
        session.setTimingThresholds(settings.timingThresholds());
        return session;
    }

    // Цвета ячеек есть только в Excel, поэтому другого формата у этого отчета нет
    private Path parseTimingReport(String value) {
        if (value.isBlank()) {
            return null;
        }
        if (!value.trim().toLowerCase(Locale.ROOT).endsWith(TimingReportPrinter.EXTENSION)) {
            throw new IllegalArgumentException("Отчет по времени правил пишется только в Excel: имя файла должно"
                    + " заканчиваться на " + TimingReportPrinter.EXTENSION + ", а задано '" + value.trim() + "'");
        }
        return Path.of(value.trim());
    }

    // Пустое значение - один поток; 0 - по числу ядер процессора
    private int parseThreads(String value) {
        if (value.isBlank()) {
            return 1;
        }
        try {
            int threads = Integer.parseInt(value.trim());
            if (threads >= 0) {
                return threads;
            }
        } catch (NumberFormatException exception) {
            // Сообщение ниже общее для "не число" и "отрицательное число"
        }
        throw new IllegalArgumentException("Число потоков должно быть целым неотрицательным числом, а задано '"
                + value.trim() + "'");
    }

    // --scan-sql и --scan-sql=true включают проверку, --scan-sql=false отключает; без аргумента решает zond.scan.sql.
    // Вид файлов, о котором ничего не сказано, проверяется
    private boolean resolveScanned(ApplicationArguments args, FileKind kind) {
        String value = args.containsOption(kind.option()) ? optionValue(args, kind.option()) : settings.scanEnabled(kind);
        if (value == null || value.isBlank()) {
            return true;
        }
        String flag = value.trim();
        if (!TRUE.equalsIgnoreCase(flag) && !FALSE.equalsIgnoreCase(flag)) {
            throw new IllegalArgumentException("Настройка '" + kind.property() + "' принимает true или false, а задано '"
                    + flag + "'");
        }
        return Boolean.parseBoolean(flag);
    }

    // --skip-tests и --skip-tests=true включают пропуск, --skip-tests=false отменяет заданный в app.properties
    private boolean resolveSkipTests(ApplicationArguments args) {
        if (!args.containsOption(SKIP_TESTS_OPTION)) {
            return settings.skipTests();
        }
        String value = optionValue(args, SKIP_TESTS_OPTION);
        return value == null || value.isBlank() || Boolean.parseBoolean(value.trim());
    }

    private String optionValue(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
