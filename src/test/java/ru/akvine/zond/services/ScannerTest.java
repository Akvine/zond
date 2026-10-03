package ru.akvine.zond.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.printers.FilePrinter;
import ru.akvine.zond.printers.ReportFormatter;
import ru.akvine.zond.rules.CheckAutowiredOnStaticFieldRule;
import ru.akvine.zond.rules.CheckDdlAutoRule;
import ru.akvine.zond.rules.CheckFieldInjectionRule;
import ru.akvine.zond.rules.CheckSecretInConfigRule;
import ru.akvine.zond.rules.CheckTransactionOnPrivateMethodRule;
import ru.akvine.zond.rules.CheckTransactionalSelfInvocationRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScannerTest {
    private final Scanner scanner = new Scanner(
            new FileSystemSourceLoader(),
            new FileSystemConfigLoader(),
            List.of(new CheckTransactionOnPrivateMethodRule()),
            (number, total, rule) -> {},
                RuleSettings.empty());

    @Test
    void reportsProgressForEachRuleInCodeOrder(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Sample.java"), "class Sample {}");
        List<String> progress = new ArrayList<>();
        Scanner ordered = new Scanner(
                new FileSystemSourceLoader(),
                new FileSystemConfigLoader(),
                List.of(
                        new CheckAutowiredOnStaticFieldRule(),
                        new CheckTransactionalSelfInvocationRule(),
                        new CheckTransactionOnPrivateMethodRule()),
                (number, total, rule) -> progress.add(number + " / " + total + " " + rule.code()),
                RuleSettings.empty());

        ordered.scan(dir);

        assertThat(progress).containsExactly("1 / 3 jr:1", "2 / 3 jr:2", "3 / 3 jr:4");
    }

    @Test
    void scansDirectoryAndWritesReport(@TempDir Path dir) throws IOException {
        Path sources = Files.createDirectories(dir.resolve("src/nested"));
        Files.writeString(sources.resolve("Bad.java"), """
                class Bad {
                    @Transactional
                    private void save() {}
                }
                """);
        Files.writeString(sources.resolve("Good.java"), """
                class Good {
                    @Transactional
                    public void save() {}
                }
                """);
        Files.writeString(sources.resolve("Broken.java"), "class Broken {");
        Files.writeString(sources.resolve("notes.txt"), "@Transactional private void x() {}");

        ScanResult result = scanner.scan(dir.resolve("src"));

        assertThat(result.filesCount()).isEqualTo(2);
        assertThat(result.rulesCount()).isEqualTo(1);
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0).file().getFileName()).hasToString("Bad.java");
        assertThat(result.violations().get(0).line()).isEqualTo(2);
        assertThat(result.failedFiles()).extracting(path -> path.getFileName().toString())
                .containsExactly("Broken.java");

        Path report = dir.resolve("out/report.txt");
        new FilePrinter(new ReportFormatter(), report).print(result);

        assertThat(Files.readString(report))
                .contains("Найдено проблем: 1")
                .contains("[jr:1]")
                .contains("Bad.java:2")
                .contains("Broken.java");
    }

    @Test
    void skipsRulesDisabledByOptions(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Sample.java"), """
                @Service
                class Sample {
                    @Autowired
                    private Repository repository;

                    @Transactional
                    private void save() {}
                }
                """);
        // jr:1 - CRITICAL, jr:6 - MINOR
        Scanner twoRules = new Scanner(
                new FileSystemSourceLoader(),
                new FileSystemConfigLoader(),
                List.of(new CheckTransactionOnPrivateMethodRule(), new CheckFieldInjectionRule()),
                (number, total, rule) -> {},
                RuleSettings.empty());

        assertThat(twoRules.scan(dir).violations()).extracting(violation -> violation.ruleCode())
                .containsExactly("jr:6", "jr:1");

        ScanResult byCode = twoRules.scan(dir, ScanOptions.parse("jr:1", ""));
        assertThat(byCode.violations()).extracting(violation -> violation.ruleCode()).containsExactly("jr:6");
        assertThat(byCode.rulesCount()).isEqualTo(1);
        assertThat(byCode.disabledRulesCount()).isEqualTo(1);

        ScanResult byName = twoRules.scan(dir, ScanOptions.parse("CheckFieldInjectionRule", ""));
        assertThat(byName.violations()).extracting(violation -> violation.ruleCode()).containsExactly("jr:1");

        ScanResult byLevel = twoRules.scan(dir, ScanOptions.parse("", "MAJOR"));
        assertThat(byLevel.violations()).extracting(violation -> violation.ruleCode()).containsExactly("jr:1");
        assertThat(byLevel.disabledRulesCount()).isEqualTo(1);
    }

    @Test
    void hidesViolationsSuppressedByComments(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bad.java"), """
                class Bad {
                    @Transactional
                    private void first() {}
                    // zond:ignore jr:1 - знаем, исправим позже
                    @Transactional
                    private void second() {}
                    @Transactional // zond:ignore
                    private void third() {}
                    // zond:ignore jr:999
                    @Transactional
                    private void fourth() {}
                }
                """);
        Files.writeString(dir.resolve("FileLevel.java"), """
                // zond:ignore-file CheckTransactionOnPrivateMethodRule
                class FileLevel {
                    @Transactional
                    private void save() {}
                }
                """);
        // Слово внутри строкового литерала подавлением не считается
        Files.writeString(dir.resolve("Literal.java"), """
                class Literal {
                    @Transactional private void save() { String text = "zond:ignore"; }
                }
                """);

        ScanResult result = scanner.scan(dir);

        assertThat(result.violations())
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("Bad.java:2", "Bad.java:10", "Literal.java:2");
        assertThat(result.suppressedCount()).isEqualTo(3);
        assertThat(new ReportFormatter().format(result))
                .contains("Найдено проблем: 3 (скрыто комментариями zond:ignore: 3)");
    }

    @Test
    void skipsTestDirectoriesWhenAsked(@TempDir Path dir) throws IOException {
        String code = """
                class Sample {
                    @Transactional
                    private void save() {}
                }
                """;
        Path main = Files.createDirectories(dir.resolve("src/main/java"));
        Path test = Files.createDirectories(dir.resolve("src/test/java/nested"));
        Files.writeString(main.resolve("Main.java"), code);
        Files.writeString(test.resolve("MainTest.java"), code);
        Files.writeString(dir.resolve("src/test/application.properties"), "spring.jpa.hibernate.ddl-auto=validate\n");
        ScanOptions skipTests = ScanOptions.defaults().withSkipTests(true);

        ScanResult everything = scanner.scan(dir);
        assertThat(everything.filesCount()).isEqualTo(3);
        assertThat(everything.violations()).hasSize(2);
        assertThat(everything.testsSkipped()).isFalse();

        ScanResult withoutTests = scanner.scan(dir, skipTests);
        assertThat(withoutTests.filesCount()).isEqualTo(1);
        assertThat(withoutTests.violations())
                .extracting(violation -> violation.file().getFileName().toString())
                .containsExactly("Main.java");
        assertThat(new ReportFormatter().format(withoutTests)).contains("Файлов проверено: 1 (каталоги test пропущены)");

        // Если сканировать попросили сам каталог test, он проверяется
        assertThat(scanner.scan(dir.resolve("src/test"), skipTests).violations()).hasSize(1);
    }

    @Test
    void suppressesViolationsInConfigFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("application.properties"), """
                spring.jpa.hibernate.ddl-auto=update
                # zond:ignore jr:204
                spring.datasource.password=s3cret
                app.api.token=abcdef
                """);
        Scanner configRules = new Scanner(
                new FileSystemSourceLoader(),
                new FileSystemConfigLoader(),
                List.of(new CheckDdlAutoRule(), new CheckSecretInConfigRule()),
                (number, total, rule) -> {},
                RuleSettings.empty());

        ScanResult result = configRules.scan(dir);

        assertThat(result.violations()).extracting(violation -> violation.ruleCode() + ":" + violation.line())
                .containsExactly("jr:200:1", "jr:204:4");
        assertThat(result.suppressedCount()).isEqualTo(1);
    }
}
