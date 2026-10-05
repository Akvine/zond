package ru.akvine.zond.cli;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.loaders.MavenClasspathResolver;
import ru.akvine.zond.printers.PrinterFactory;
import ru.akvine.zond.printers.ReportFormatter;
import ru.akvine.zond.printers.RuleListFormatter;
import ru.akvine.zond.printers.RuleListWriter;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.logical.CheckSqlDestructiveStatementRule;
import ru.akvine.zond.rules.logical.CheckTransactionOnPrivateMethodRule;
import ru.akvine.zond.services.RuleCatalog;
import ru.akvine.zond.services.Scanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class ScanRunnerTest {
    // Пункты главного меню
    private static final String SCAN = "1";
    private static final String SETTINGS = "3";
    private static final String RULES = "4";
    private static final String EXIT = "5";

    // Пустая строка в выборе папки - отказ от выбора
    private static final String CANCEL = "";

    @TempDir
    Path dir;

    @TempDir
    Path configDir;

    // Настройки правил (zond.rule.*), с которыми создается приложение
    private RuleSettings ruleSettings = RuleSettings.empty();

    // Настройки zond.scan.<вид файлов> из app.properties
    private final MockEnvironment environment = new MockEnvironment();

    private List<Rule> rules = List.of(new CheckTransactionOnPrivateMethodRule());

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(dir.resolve("Bad.java"), """
                class Bad {
                    @Transactional
                    private void save() {}
                }
                """);
    }

    @Test
    void scansFolderChosenInMenuAndWritesReportFromSettings() throws IOException {
        Path report = dir.resolve("settings-report.txt");
        ScanRunner runner = runner(report.toString(), "\"" + dir + "\"", SCAN, EXIT);

        runner.run(new DefaultApplicationArguments());

        assertThat(Files.readString(report)).contains("Найдено проблем: 1");
        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void asksForFolderWhenScanStartedWithoutOne() {
        Path report = dir.resolve("settings-report.txt");
        ScanRunner runner = runner(report.toString(), CANCEL, SCAN, dir.toString(), EXIT);

        runner.run(new DefaultApplicationArguments());

        assertThat(report).exists();
        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void reportArgumentOverridesSettings() {
        Path fromSettings = dir.resolve("settings-report.txt");
        Path fromArgument = dir.resolve("argument-report.txt");
        ScanRunner runner = runner(fromSettings.toString());

        runner.run(new DefaultApplicationArguments("--path=" + dir, "--report=" + fromArgument));

        assertThat(fromArgument).exists();
        assertThat(fromSettings).doesNotExist();
    }

    @Test
    void stopsWhenInputEnds() {
        ScanRunner runner = runner("");

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void wrongMenuItemIsAskedAgain() {
        ScanRunner runner = runner("", CANCEL, "9", "abc", EXIT);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void settingsChangedInMenuAreSavedAndApplied() throws IOException {
        Path config = configDir.resolve("app.properties");
        Files.writeString(config, """
                # мои настройки
                zond.report.path=old.txt
                other.property=1
                """);
        Path reports = dir.resolve("reports");

        ScanRunner runner = runner(dir.resolve("old.txt").toString(),
                dir.toString(),
                SETTINGS,
                "1", reports.toString(),       // папка для отчета
                "2", "3",                      // формат: xlsx
                "3",                           // тесты: не сканировать
                "4", "2",                      // минимальный уровень: CRITICAL
                "5", "2",                      // минимальная уверенность: PROBABLE
                "6", "1", "jr:1, jr:999", "4", // отключить правило, неизвестное пропускается
                "7", "generated, *Dto.java",   // исключенные пути
                "8", "1", "9",                 // миграции БД: не проверять
                "9",
                SCAN, EXIT);

        runner.run(new DefaultApplicationArguments());

        assertThat(Files.readAllLines(config)).containsExactly(
                "# мои настройки",
                "zond.report.path=" + reports.resolve("old.xlsx").toString().replace('\\', '/'),
                "other.property=1",
                "zond.rules.disabled=jr:1",
                "zond.rules.min-level=CRITICAL",
                "zond.rules.min-confidence=PROBABLE",
                "zond.scan.skip-tests=true",
                "zond.scan.exclude=generated, *Dto.java",
                "zond.scan.sql=false",
                "zond.scan.build-files=true",
                "zond.scan.docker=true",
                "zond.scan.config=true",
                "zond.scan.messages=true",
                "zond.scan.logging=true",
                "zond.scan.kubernetes=true",
                "zond.scan.ci=true");
        // Единственное правило отключено - проблем нет, отчет лежит в новой папке
        assertThat(reports.resolve("old.xlsx")).exists();
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void disabledRuleCanBeEnabledBack() throws IOException {
        ScanRunner runner = runner("",
                CANCEL,
                SETTINGS,
                "6", "1", "CheckTransactionOnPrivateMethodRule", "2", "jr:1", "4",
                "9",
                EXIT);

        runner.run(new DefaultApplicationArguments());

        assertThat(Files.readAllLines(configDir.resolve("app.properties"))).contains("zond.rules.disabled=");
    }

    @Test
    void exportsRuleListNextToReports() throws IOException {
        ScanRunner runner = runner(dir.resolve("report.txt").toString(),
                CANCEL, RULES, "1", "2", "3", "4", EXIT);

        runner.run(new DefaultApplicationArguments("--disable=jr:1"));

        assertThat(Files.readString(dir.resolve("zond-rules.txt")))
                .contains("Всего правил: 1, активно: 0, отключено: 1")
                .contains("jr:1  CheckTransactionOnPrivateMethodRule  [отключено]");
        try (InputStream input = Files.newInputStream(dir.resolve("zond-rules.xlsx"));
             Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Код");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("jr:1");
            assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).isEqualTo("отключено");
        }
    }

    @Test
    void minLevelArgumentHidesLessSevereRules() {
        ScanRunner runner = runner("");

        // Единственное правило имеет уровень CRITICAL - при пороге BLOCKER оно не запускается
        runner.run(new DefaultApplicationArguments("--path=" + dir, "--min-level=BLOCKER"));

        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void disableArgumentSwitchesRuleOff() {
        ScanRunner runner = runner("");

        runner.run(new DefaultApplicationArguments("--path=" + dir, "--disable=jr:1"));

        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void excludeArgumentSkipsMatchingPaths() throws IOException {
        // Единственный файл с нарушением переносим в каталог, который исключаем
        Path generated = Files.createDirectories(dir.resolve("src/generated"));
        Files.move(dir.resolve("Bad.java"), generated.resolve("Bad.java"));

        ScanRunner everything = runner("");
        everything.run(new DefaultApplicationArguments("--path=" + dir));
        assertThat(everything.getExitCode()).isEqualTo(1);

        ScanRunner withoutGenerated = runner("");
        withoutGenerated.run(new DefaultApplicationArguments("--path=" + dir, "--exclude=generated"));
        assertThat(withoutGenerated.getExitCode()).isZero();
    }

    @Test
    void skipTestsArgumentExcludesTestDirectories() throws IOException {
        // Единственный файл с нарушением переносим в каталог test
        Path test = Files.createDirectories(dir.resolve("src/test/java"));
        Files.move(dir.resolve("Bad.java"), test.resolve("Bad.java"));

        ScanRunner withTests = runner("");
        withTests.run(new DefaultApplicationArguments("--path=" + dir));
        assertThat(withTests.getExitCode()).isEqualTo(1);

        ScanRunner withoutTests = runner("");
        withoutTests.run(new DefaultApplicationArguments("--path=" + dir, "--skip-tests"));
        assertThat(withoutTests.getExitCode()).isZero();
    }

    @Test
    void threadsArgumentRunsScanInParallel() {
        ScanRunner parallel = runner("");
        parallel.run(new DefaultApplicationArguments("--path=" + dir, "--threads=4"));
        assertThat(parallel.getExitCode()).isEqualTo(1);

        // 0 - по числу ядер процессора
        ScanRunner allCores = runner("");
        allCores.run(new DefaultApplicationArguments("--path=" + dir, "--threads=0"));
        assertThat(allCores.getExitCode()).isEqualTo(1);
    }

    @Test
    void wrongThreadsArgumentIsAnError() {
        ScanRunner notNumber = runner("");
        notNumber.run(new DefaultApplicationArguments("--path=" + dir, "--threads=many"));
        assertThat(notNumber.getExitCode()).isEqualTo(2);

        ScanRunner negative = runner("");
        negative.run(new DefaultApplicationArguments("--path=" + dir, "--threads=-1"));
        assertThat(negative.getExitCode()).isEqualTo(2);
    }

    @Test
    void fileKindsAreSwitchedOffBySettingsAndArguments() throws IOException {
        // Правило для Java находок не даст: нарушение остается только в SQL-файле
        Files.delete(dir.resolve("Bad.java"));
        Files.writeString(dir.resolve("V1__init.sql"), "drop table old_table;\n");
        rules = List.of(new CheckSqlDestructiveStatementRule());

        ScanRunner everything = runner("");
        everything.run(new DefaultApplicationArguments("--path=" + dir));
        assertThat(everything.getExitCode()).isEqualTo(1);

        ScanRunner byArgument = runner("");
        byArgument.run(new DefaultApplicationArguments("--path=" + dir, "--scan-sql=false"));
        assertThat(byArgument.getExitCode()).isZero();

        // Другой вид файлов на SQL не влияет
        ScanRunner otherKind = runner("");
        otherKind.run(new DefaultApplicationArguments("--path=" + dir, "--scan-docker=false"));
        assertThat(otherKind.getExitCode()).isEqualTo(1);

        environment.setProperty("zond.scan.sql", "false");
        ScanRunner bySetting = runner("");
        bySetting.run(new DefaultApplicationArguments("--path=" + dir));
        assertThat(bySetting.getExitCode()).isZero();

        // Аргумент важнее настройки
        ScanRunner argumentWins = runner("");
        argumentWins.run(new DefaultApplicationArguments("--path=" + dir, "--scan-sql"));
        assertThat(argumentWins.getExitCode()).isEqualTo(1);

        environment.setProperty("zond.scan.sql", "нет");
        ScanRunner wrong = runner("");
        wrong.run(new DefaultApplicationArguments("--path=" + dir));
        assertThat(wrong.getExitCode()).isEqualTo(2);
    }

    @Test
    void minConfidenceArgumentHidesLessCertainFindings() {
        // Единственное правило дает находки с уверенностью "вероятно"
        ScanRunner all = runner("");
        all.run(new DefaultApplicationArguments("--path=" + dir, "--min-confidence=probable"));
        assertThat(all.getExitCode()).isEqualTo(1);

        ScanRunner confirmedOnly = runner("");
        confirmedOnly.run(new DefaultApplicationArguments("--path=" + dir, "--min-confidence=CONFIRMED"));
        assertThat(confirmedOnly.getExitCode()).isZero();

        ScanRunner wrong = runner("");
        wrong.run(new DefaultApplicationArguments("--path=" + dir, "--min-confidence=sure"));
        assertThat(wrong.getExitCode()).isEqualTo(2);
    }

    @Test
    void unknownMinLevelIsAnError() {
        ScanRunner runner = runner("");

        runner.run(new DefaultApplicationArguments("--path=" + dir, "--min-level=HIGH"));

        assertThat(runner.getExitCode()).isEqualTo(2);
    }

    @Test
    void ruleLevelFromSettingsIsApplied() throws IOException {
        // Единственное правило понижено до INFO - при пороге MAJOR оно не запускается
        ruleSettings = RuleSettings.of(Map.of("jr-1.level", "info"));
        ScanRunner hidden = runner("");
        hidden.run(new DefaultApplicationArguments("--path=" + dir, "--min-level=MAJOR"));
        assertThat(hidden.getExitCode()).isZero();

        Path report = dir.resolve("level-report.txt");
        ScanRunner shown = runner(report.toString());
        shown.run(new DefaultApplicationArguments("--path=" + dir));
        assertThat(Files.readString(report)).contains("[INFO]").doesNotContain("[CRITICAL]");
    }

    @Test
    void mistakeInRuleSettingsIsAnError() {
        ruleSettings = RuleSettings.of(Map.of("jr-999.level", "INFO"));
        ScanRunner runner = runner("");

        runner.run(new DefaultApplicationArguments("--path=" + dir));

        assertThat(runner.getExitCode()).isEqualTo(2);
    }

    private ScanRunner runner(String reportPath, String... inputLines) {
        Queue<String> input = new ArrayDeque<>(List.of(inputLines));
        ConsoleInput consoleInput = new ConsoleInput() {
            @Override
            public String readLine(String prompt) {
                return input.poll();
            }
        };

        RuleCatalog catalog = new RuleCatalog(rules, ruleSettings);
        ConsoleMenu menu = new ConsoleMenu(consoleInput);
        FolderPicker folderPicker = new FolderPicker(consoleInput);
        RuleListFormatter ruleListFormatter = new RuleListFormatter();
        ScanExecutor executor = new ScanExecutor(
                new Scanner(
                        new FileSystemSourceLoader(),
                        new FileSystemConfigLoader(),
                        new FileSystemTextFileLoader(),
                        rules,
                        (number, total, rule) -> {},
                        ruleSettings),
                new PrinterFactory(new ReportFormatter()),
                new MavenClasspathResolver(configDir.resolve("repository")));
        MainMenu mainMenu = new MainMenu(
                menu,
                folderPicker,
                new SettingsMenu(menu, consoleInput, folderPicker, catalog,
                        new SettingsStore(configDir.resolve("app.properties").toString())),
                new RulesMenu(menu, catalog, ruleListFormatter, new RuleListWriter(ruleListFormatter)),
                executor);
        return new ScanRunner(executor, mainMenu, new ZondSettings(reportPath, "", "", "", "", "", "", environment), catalog);
    }
}
