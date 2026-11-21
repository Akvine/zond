package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.GitIgnore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Дамп памяти, файл базы или лог в папке проекта, не закрытый .gitignore. В дампе памяти лежит все, что было
 * в памяти процесса: пароли, токены, данные клиентов. Один git add . - и файл уходит в репозиторий насовсем.
 */
@Component
public class DumpFileInProjectRule extends AbstractContextRule {
    // Каталоги сборки и служебные: их содержимое в репозиторий не попадает
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "build", "target", "out", "node_modules", ".git", ".gradle", ".idea", ".mvn", ".settings");
    private static final int MAX_DEPTH = 6;
    private static final int MAX_FILES = 50_000;

    private enum Kind {
        HEAP_DUMP(Pattern.compile(".*\\.hprof(\\.gz)?"), ErrorLevel.MAJOR,
                "Дамп памяти JVM: в нем все, что было в памяти процесса, - пароли, токены, данные клиентов"),
        DATABASE(Pattern.compile(".*\\.(mv\\.db|trace\\.db|h2\\.db|sqlite3?|db)"), ErrorLevel.MAJOR,
                "Файл базы данных: в нем данные, с которыми работало приложение"),
        CRASH_LOG(Pattern.compile("hs_err_pid\\d+\\.log"), ErrorLevel.MINOR,
                "Отчет об аварии JVM: в нем параметры запуска и переменные окружения"),
        LOG(Pattern.compile(".*\\.log(\\.[\\w-]+)*"), ErrorLevel.MINOR,
                "Файл лога: в логи попадают адреса, идентификаторы и тексты запросов");

        private final Pattern name;
        private final ErrorLevel level;
        private final String risk;

        Kind(Pattern name, ErrorLevel level, String risk) {
            this.name = name;
            this.level = level;
            this.risk = risk;
        }

        private static Optional<Kind> of(Path file) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.name.matcher(name).matches()) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public String code() {
        return RuleCodes.DUMP_FILE_IN_PROJECT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует папку проекта и ищет дампы памяти, файлы баз и логи, не закрытые .gitignore";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Path root = context.root().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        GitIgnore ignore = GitIgnore.of(root);
        boolean repository = ignore.isRepository();
        List<Path> files = new ArrayList<>();
        findFiles(root, files);
        List<Violation> violations = new ArrayList<>();
        for (Path file : files) {
            Optional<Kind> kind = Kind.of(file);
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (kind.isEmpty() || ignore.isIgnored(file)) {
                continue;
            }
            // Без .git и .gitignore неизвестно, хранится ли папка в репозитории вообще
            violations.add(violation(file, kind.get().risk + ". Файл '" + relative + "' лежит в папке проекта и не"
                    + " закрыт .gitignore: он попадет в репозиторий с первым же git add; удалите его и добавьте"
                    + " шаблон в .gitignore")
                    .withLevel(kind.get().level)
                    .withConfidence(repository ? Confidence.PROBABLE : Confidence.SUSPICION));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // Попал ли файл в репозиторий на самом деле, без git не узнать
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    private void findFiles(Path root, List<Path> found) {
        try (Stream<Path> files = Files.walk(root, MAX_DEPTH)) {
            files.limit(MAX_FILES)
                    .filter(file -> !isInSkippedDirectory(root.relativize(file)))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .forEach(found::add);
        } catch (IOException | UncheckedIOException exception) {
            // Папка не прочиталась целиком - сообщаем о том, что успели найти
        }
    }

    private boolean isInSkippedDirectory(Path relative) {
        for (Path part : relative) {
            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
