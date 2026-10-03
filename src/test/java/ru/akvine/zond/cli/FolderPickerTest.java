package ru.akvine.zond.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolderPickerTest {
    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws IOException {
        // В списке: 1 - переход наверх, 2 - alpha, 3 - beta; скрытая папка и файл не показываются
        Files.createDirectories(dir.resolve("alpha/inner"));
        Files.createDirectories(dir.resolve("beta"));
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve("Main.java"), "class Main {}");
    }

    @Test
    void zeroSelectsShownFolder() {
        assertThat(pick(false, "0")).contains(dir);
    }

    @Test
    void numberOpensSubfolder() {
        assertThat(pick(false, "3", "0")).contains(dir.resolve("beta"));
        assertThat(pick(false, "2", "2", "0")).contains(dir.resolve("alpha/inner"));
    }

    @Test
    void firstItemGoesUp() {
        assertThat(pick(false, "2", "1", "0")).contains(dir);
    }

    @Test
    void typedPathIsSelectedAtOnce() {
        assertThat(pick(false, "alpha/inner")).contains(dir.resolve("alpha/inner"));
        assertThat(pick(false, "\"" + dir.resolve("beta") + "\"")).contains(dir.resolve("beta"));
        assertThat(pick(false, "Main.java")).contains(dir.resolve("Main.java"));
    }

    @Test
    void wrongAnswerIsAskedAgain() {
        assertThat(pick(false, "99", "missing", "0")).contains(dir);
    }

    @Test
    void missingFolderIsAllowedOnlyForNewOnes() {
        assertThat(pick(true, "reports")).contains(dir.resolve("reports"));
    }

    @Test
    void emptyLineCancels() {
        assertThat(pick(false, "")).isEmpty();
    }

    @Test
    void endOfInputIsReported() {
        assertThatThrownBy(() -> pick(false)).isInstanceOf(InputClosedException.class);
    }

    private Optional<Path> pick(boolean allowNew, String... inputLines) {
        Queue<String> input = new ArrayDeque<>(List.of(inputLines));
        ConsoleInput consoleInput = new ConsoleInput() {
            @Override
            public String readLine(String prompt) {
                return input.poll();
            }
        };
        return new FolderPicker(consoleInput).pick(dir, "Выберите папку", allowNew);
    }
}
