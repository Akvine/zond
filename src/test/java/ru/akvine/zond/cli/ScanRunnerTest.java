package ru.akvine.zond.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import ru.akvine.zond.config.ZondSettings;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.printers.PrinterFactory;
import ru.akvine.zond.printers.ReportFormatter;
import ru.akvine.zond.rules.CheckTransactionOnPrivateMethodRule;
import ru.akvine.zond.services.Scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class ScanRunnerTest {
    @TempDir
    Path dir;

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
    void readsPathFromConsoleAndReportPathFromSettings() throws IOException {
        Path report = dir.resolve("settings-report.txt");
        ScanRunner runner = runner(report.toString(), "\"" + dir + "\"", "exit");

        runner.run(new DefaultApplicationArguments());

        assertThat(Files.readString(report)).contains("Найдено проблем: 1");
        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void reportArgumentOverridesSettings() throws IOException {
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

    private ScanRunner runner(String reportPath, String... inputLines) {
        Queue<String> input = new ArrayDeque<>(List.of(inputLines));
        ConsoleInput consoleInput = new ConsoleInput() {
            @Override
            public String readLine(String prompt) {
                return input.poll();
            }
        };
        return new ScanRunner(
                new Scanner(
                        new FileSystemSourceLoader(),
                        new FileSystemConfigLoader(),
                        List.of(new CheckTransactionOnPrivateMethodRule()),
                        (number, total, rule) -> {}),
                new PrinterFactory(new ReportFormatter()),
                new ZondSettings(reportPath),
                consoleInput);
    }
}
