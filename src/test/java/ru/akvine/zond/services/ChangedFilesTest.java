package ru.akvine.zond.services;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.cli.SessionSettings;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.loaders.GitChangedFiles;
import ru.akvine.zond.loaders.HashChangedFiles;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.models.ScanResult;
import ru.akvine.zond.printers.ReportFormatter;
import ru.akvine.zond.rules.codesmell.UnusedClassRule;
import ru.akvine.zond.rules.logical.TransactionOnPrivateMethodRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверка только измененных файлов: что считается измененным по данным git и по хешам файлов и как это
 * меняет результат сканирования
 */
class ChangedFilesTest {
    private static final String BROKEN = """
            @Service
            class %s {
                @Transactional
                private void save() {}
            }
            """;

    @TempDir
    Path dir;

    private final GitChangedFiles changedFiles = new GitChangedFiles();

    @Test
    void findsUncommittedAndUntrackedFiles() throws Exception {
        Assumptions.assumeTrue(gitWorks(), "git не установлен");
        git("init", "-q");
        write("Committed.java", "class Committed {}");
        write("Edited.java", "class Edited {}");
        write("Removed.java", "class Removed {}");
        commit("первый");
        write("Edited.java", "class Edited { int value; }");
        write("sub/New.java", "class New {}");
        Files.delete(dir.resolve("Removed.java"));

        GitChangedFiles.Changes changes = changedFiles.find(dir, "").orElseThrow();

        // Удаленный файл не входит: проверять в нем нечего
        assertThat(changes.base()).isEqualTo("HEAD");
        assertThat(names(changes.files())).containsExactlyInAnyOrder("Edited.java", "sub/New.java");
    }

    @Test
    void comparesWithBranchPoint() throws Exception {
        Assumptions.assumeTrue(gitWorks(), "git не установлен");
        git("init", "-q");
        write("Base.java", "class Base {}");
        commit("база");
        git("branch", "start");
        write("Feature.java", "class Feature {}");
        commit("изменение ветки");
        write("Draft.java", "class Draft {}");

        // Сравнение с веткой: и уже закоммиченное после нее, и то, что еще в работе
        assertThat(names(changedFiles.find(dir, "start").orElseThrow().files()))
                .containsExactlyInAnyOrder("Feature.java", "Draft.java");
        // Сравнение с HEAD: только незакоммиченное
        assertThat(names(changedFiles.find(dir, "").orElseThrow().files())).containsExactly("Draft.java");
        // Изменения берутся только внутри сканируемой папки
        Files.createDirectories(dir.resolve("empty"));
        assertThat(changedFiles.find(dir.resolve("empty"), "start").orElseThrow().files()).isEmpty();

        assertThatThrownBy(() -> changedFiles.find(dir, "no-such-branch"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-branch");
    }

    @Test
    void everythingIsChangedBeforeFirstCommit() throws Exception {
        Assumptions.assumeTrue(gitWorks(), "git не установлен");
        git("init", "-q");
        write("Staged.java", "class Staged {}");
        git("add", "Staged.java");
        write("Loose.java", "class Loose {}");

        assertThat(names(changedFiles.find(dir, "").orElseThrow().files()))
                .containsExactlyInAnyOrder("Staged.java", "Loose.java");
    }

    @Test
    void folderOutsideRepositoryHasNoAnswer() {
        Assumptions.assumeTrue(gitWorks(), "git не установлен");
        Assumptions.assumeTrue(changedFiles.find(dir, "").isEmpty(), "временная папка лежит внутри репозитория git");

        assertThat(changedFiles.find(dir, "main")).isEmpty();
    }

    @Test
    void withoutGitFilesAreComparedByHashWithPreviousScan(@TempDir Path snapshots) throws IOException {
        HashChangedFiles hashes = new HashChangedFiles(snapshots);
        write("src/main/java/Kept.java", "class Kept {}");
        write("src/main/java/Edited.java", "class Edited {}");
        write("src/main/java/Removed.java", "class Removed {}");
        write("src/main/resources/application.yml", "server:\n  port: 8080\n");
        // Каталоги сборки и служебные, а также файлы, которые zond не проверяет, в сравнении не участвуют
        write("build/generated/Generated.java", "class Generated {}");
        write(".idea/workspace.xml", "<project/>");
        write("notes.txt", "заметки");

        // Прошлой проверки не было: измененным считается все, что проверяется
        HashChangedFiles.Changes first = hashes.find(dir, file -> true);
        assertThat(first.previous()).isNull();
        assertThat(names(first.files())).containsExactlyInAnyOrder(
                "src/main/java/Kept.java", "src/main/java/Edited.java", "src/main/java/Removed.java",
                "src/main/resources/application.yml");
        // Пока хеши не запомнены, точки отсчета по-прежнему нет
        assertThat(hashes.find(dir, file -> true).previous()).isNull();
        assertThat(hashes.remember(first.current())).isEqualTo(hashes.fileOf(dir)).isRegularFile();

        write("src/main/java/Edited.java", "class Edited { int value; }");
        // Файл переписан тем же содержимым: дата у него новая, а хеш прежний
        write("src/main/java/Kept.java", "class Kept {}");
        write("src/main/java/sub/New.java", "class New {}");
        write("build/generated/Generated.java", "class Generated { int value; }");
        Files.delete(dir.resolve("src/main/java/Removed.java"));

        // Удаленный файл не входит: проверять в нем нечего
        HashChangedFiles.Changes second = hashes.find(dir, file -> true);
        assertThat(second.previous()).isNotNull();
        assertThat(names(second.files())).containsExactlyInAnyOrder("src/main/java/Edited.java", "src/main/java/sub/New.java");

        hashes.remember(second.current());
        assertThat(hashes.find(dir, file -> true).files()).isEmpty();
        // Удаленный файл вернули: с прошлой проверки он новый
        write("src/main/java/Removed.java", "class Removed {}");
        assertThat(names(hashes.find(dir, file -> true).files())).containsExactly("src/main/java/Removed.java");
    }

    @Test
    void hashesAreKeptPerFolderAndOnlyForCheckedFiles(@TempDir Path snapshots) throws IOException {
        HashChangedFiles hashes = new HashChangedFiles(snapshots);
        write("one/src/main/java/First.java", "class First {}");
        write("one/src/test/java/FirstTest.java", "class FirstTest {}");
        write("two/Second.java", "class Second {}");
        Path one = dir.resolve("one");
        Path two = dir.resolve("two");

        // Тесты сейчас не проверяются - и не запоминаются
        hashes.remember(hashes.find(one, file -> !file.toString().contains("test")).current());

        // Когда тесты начнут проверять, они окажутся новыми, хотя их никто не менял
        assertThat(hashes.find(one, file -> true).files())
                .containsExactly(one.resolve("src/test/java/FirstTest.java").toAbsolutePath().normalize());
        // У другой папки свои хеши, и проверки первой ее не касаются
        assertThat(hashes.fileOf(two)).isNotEqualTo(hashes.fileOf(one));
        assertThat(hashes.find(two, file -> true).previous()).isNull();

        // Испорченный файл с хешами - то же, что его отсутствие: лучше проверить все, чем пропустить изменение
        Files.writeString(hashes.fileOf(one), "это не хеши\n");
        assertThat(hashes.find(one, file -> true).previous()).isNull();
        // Папки, которой нет, не существует и для хешей: об ошибке скажет сама проверка
        assertThat(hashes.find(dir.resolve("missing"), file -> true).files()).isEmpty();
    }

    @Test
    void referenceMustLookLikeGitName() {
        assertThat(GitChangedFiles.validate("  origin/main ")).isEqualTo("origin/main");
        assertThat(GitChangedFiles.validate("HEAD~3")).isEqualTo("HEAD~3");
        assertThat(GitChangedFiles.validate("v1.2.0")).isEqualTo("v1.2.0");
        assertThat(GitChangedFiles.validate("")).isEmpty();
        assertThat(GitChangedFiles.validate(null)).isEmpty();
        // Имя с пробелом или с дефисом в начале git принял бы за что-то другое
        for (String wrong : List.of("не ветка", "--upload-pack=x", "main; rm -rf", "a b")) {
            assertThatThrownBy(() -> GitChangedFiles.validate(wrong))
                    .as(wrong)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void threadCountIsValidated() {
        assertThat(SessionSettings.parseThreads("")).isEqualTo(1);
        assertThat(SessionSettings.parseThreads(" 8 ")).isEqualTo(8);
        assertThat(SessionSettings.parseThreads("0")).isZero();
        assertThat(SessionSettings.parseThreads(String.valueOf(SessionSettings.MAX_THREADS))).isEqualTo(SessionSettings.MAX_THREADS);
        for (String wrong : List.of("-1", "abc", "2.5", "129", "99999999999")) {
            assertThatThrownBy(() -> SessionSettings.parseThreads(wrong))
                    .as(wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("от 0 до " + SessionSettings.MAX_THREADS)
                    .hasMessageContaining(wrong);
        }
    }

    @Test
    void findingsAreShownOnlyInChangedFilesButProjectIsReadWhole() throws IOException {
        // Edited использует Helper: чтобы это увидеть, правилу о неиспользуемых классах нужен весь проект
        write("src/main/java/Old.java", BROKEN.formatted("Old"));
        write("src/main/java/Edited.java", """
                @Service
                class Edited {
                    private final Helper helper = new Helper();
                    @Transactional
                    private void save() {}
                }
                """);
        write("src/main/java/Helper.java", "class Helper {}");
        write("src/main/java/Lonely.java", "class Lonely {}");
        Scanner scanner = new Scanner(
                new FileSystemSourceLoader(),
                new FileSystemConfigLoader(),
                new FileSystemTextFileLoader(),
                List.of(new TransactionOnPrivateMethodRule(), new UnusedClassRule()),
                (number, total, rule) -> {},
                RuleSettings.empty());
        Set<Path> changed = Set.of(
                dir.resolve("src/main/java/Edited.java").toAbsolutePath().normalize(),
                dir.resolve("src/main/java/Helper.java").toAbsolutePath().normalize(),
                dir.resolve("README.md").toAbsolutePath().normalize());

        ScanResult everything = scanner.scan(dir);
        ScanResult changedOnly = scanner.scan(dir, ScanOptions.defaults().withChangedFiles(changed));

        assertThat(everything.changedFilesCount()).isNull();
        assertThat(everything.violations())
                .extracting(violation -> violation.ruleCode() + " " + violation.file().getFileName())
                .contains("jr:1 Old.java", "jr:1 Edited.java");

        // Old не менялся - его находка не показана. Helper изменен и не отмечен неиспользуемым: его использование
        // в Edited видно, потому что прочитан весь проект
        assertThat(changedOnly.violations())
                .extracting(violation -> violation.ruleCode() + " " + violation.file().getFileName())
                .containsExactly("jr:1 Edited.java");
        // README.md изменен, но не проверяется: в счет идут только прочитанные файлы
        assertThat(changedOnly.filesCount()).isEqualTo(4);
        assertThat(changedOnly.changedFilesCount()).isEqualTo(2);
        assertThat(new ReportFormatter().format(changedOnly)).contains("Находки показаны только в измененных файлах: 2");
        assertThat(new ReportFormatter().format(everything)).doesNotContain("измененных файлах");
    }

    private List<String> names(Set<Path> files) {
        Path root = dir.toAbsolutePath().normalize();
        return files.stream().map(file -> root.relativize(file).toString().replace('\\', '/')).toList();
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void commit(String message) throws Exception {
        git("add", "-A");
        git("-c", "user.name=zond", "-c", "user.email=zond@example.org", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", message);
    }

    private void git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(String.join(" ", command) + ": " + output).isZero();
    }

    private boolean gitWorks() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
