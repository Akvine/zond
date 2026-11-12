package ru.akvine.zond.rules.support;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.loaders.GitChangedFiles;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * История миграций в git. Миграцию, которая давно лежит в репозитории, уже применили и переписывать не будут:
 * замечание к ней - справка о том, что было сделано, а не задача. Задача - миграция, которую меняли недавно
 * или еще не закоммитили.
 */
@UtilityClass
public class MigrationHistory {
    private static final String HEAD = "HEAD";
    // Сколько дней после последнего изменения миграция считается новой
    private static final int RECENT_DAYS = 30;
    private static final String NAMES_SEPARATOR = "[\\u0000\\n]";
    private static final String SETTLED_NOTE = ". Файл миграции не менялся в git больше " + RECENT_DAYS
            + " дней - похоже, она уже применена, и это справка, а не задача";

    // История последнего проекта: правил о миграциях несколько, а история у них одна
    private static Path cachedRoot;
    private static History cached = History.UNKNOWN;

    /**
     * @param known  false, если папка не лежит в репозитории git либо git не установлен
     * @param recent файлы, измененные в коммитах за последние дни, измененные без коммита и еще не добавленные в git
     */
    private record History(boolean known, Set<Path> recent) {
        private static final History UNKNOWN = new History(false, Set.of());
    }

    /**
     * Понижает до INFO находки в миграциях, которые давно не менялись. Если истории нет, находки остаются как есть
     */
    public List<Violation> soften(Path root, List<Violation> violations) {
        History history = violations.isEmpty() ? History.UNKNOWN : historyOf(root);
        if (!history.known()) {
            return violations;
        }
        List<Violation> result = new ArrayList<>();
        for (Violation violation : violations) {
            boolean settled = !history.recent().contains(real(violation.file()));
            result.add(settled
                    ? new Violation(ErrorLevel.INFO, violation.errorType(), violation.ruleCode(), violation.ruleName(),
                    violation.file(), violation.line(), violation.message() + SETTLED_NOTE, violation.confidence())
                    : violation);
        }
        return result;
    }

    private synchronized History historyOf(Path root) {
        if (!root.equals(cachedRoot)) {
            cached = readHistory(root);
            cachedRoot = root;
        }
        return cached;
    }

    private History readHistory(Path root) {
        Path directory = Files.isDirectory(root) ? root : root.toAbsolutePath().getParent();
        String top = GitChangedFiles.run(directory, "rev-parse", "--show-toplevel").map(String::trim).orElse("");
        if (top.isEmpty()) {
            return History.UNKNOWN;
        }
        String committed = GitChangedFiles.run(directory,
                "log", "--since=" + RECENT_DAYS + ".days.ago", "--name-only", "--pretty=format:", "-z").orElse(null);
        if (committed == null) {
            return History.UNKNOWN;
        }
        Path repository = real(Path.of(top));
        Set<Path> recent = new HashSet<>();
        add(recent, repository, committed);
        GitChangedFiles.run(directory, "diff", "--name-only", "-z", HEAD)
                .ifPresent(changed -> add(recent, repository, changed));
        GitChangedFiles.run(directory, "ls-files", "--others", "--exclude-standard", "--full-name", "-z")
                .ifPresent(untracked -> add(recent, repository, untracked));
        return new History(true, recent);
    }

    private void add(Set<Path> files, Path repository, String names) {
        for (String name : names.split(NAMES_SEPARATOR)) {
            if (!name.isBlank()) {
                files.add(repository.resolve(name.trim()).normalize());
            }
        }
    }

    // Путь, каким его видит файловая система: git и сканер могут назвать одну папку по-разному - через ссылку,
    // в другом регистре или коротким именем Windows
    private Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return path.toAbsolutePath().normalize();
        }
    }
}
