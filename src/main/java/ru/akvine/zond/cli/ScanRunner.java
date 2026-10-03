package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.services.RuleCatalog;

import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ScanRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final String PATH_OPTION = "path";
    private static final String REPORT_OPTION = "report";
    private static final String DISABLE_OPTION = "disable";
    private static final String MIN_LEVEL_OPTION = "min-level";
    private static final String SKIP_TESTS_OPTION = "skip-tests";
    private static final String CLASSPATH_OPTION = "classpath";
    private static final String RULES_SEPARATOR = ",";

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
        return session;
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
