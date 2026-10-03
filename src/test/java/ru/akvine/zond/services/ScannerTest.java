package ru.akvine.zond.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.printers.FilePrinter;
import ru.akvine.zond.printers.ReportFormatter;
import ru.akvine.zond.rules.CheckAutowiredOnStaticFieldRule;
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
            (number, total, rule) -> {});

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
                (number, total, rule) -> progress.add(number + " / " + total + " " + rule.code()));

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
}
