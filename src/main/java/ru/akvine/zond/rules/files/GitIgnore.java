package ru.akvine.zond.rules.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Что в проверяемой папке закрыто {@code .gitignore}: такого файла в хранилище нет, и на чистой копии проекта
 * его не окажется. Читаются {@code .gitignore} самой папки и каталогов выше нее (модуль внутри хранилища).
 * <p>
 * Разбор упрощенный: шаблон без косой черты действует на любом уровне вложенности, с косой чертой - от каталога
 * своего файла; каталог закрывает все, что в нем лежит; из подходящих правил действует последнее, а правило с
 * восклицательным знаком возвращает файл обратно
 */
public final class GitIgnore {
    private static final String GIT_IGNORE = ".gitignore";
    private static final String GIT_DIRECTORY = ".git";
    private static final Pattern OUTER_SLASHES = Pattern.compile("^/+|/+$");
    // .gitignore может лежать выше проверяемой папки: модуль внутри хранилища
    private static final int MAX_PARENTS = 4;

    /**
     * Одна строка .gitignore
     *
     * @param base    каталог файла, в котором записано правило: от него считаются пути
     * @param negated правило с восклицательным знаком: файл возвращается в хранилище
     */
    private record Rule(Path base, Pattern pattern, boolean negated) {
    }

    private final List<Rule> rules = new ArrayList<>();
    private final boolean repository;

    private GitIgnore(Path root) {
        // Правила каталога, который ближе к файлу, сильнее правил каталогов над ним: читаем сверху вниз
        List<Path> directories = new ArrayList<>();
        Path directory = root;
        for (int level = 0; level <= MAX_PARENTS && directory != null; level++) {
            directories.add(0, directory);
            directory = directory.getParent();
        }
        boolean found = false;
        for (Path current : directories) {
            Path ignoreFile = current.resolve(GIT_IGNORE);
            found = found || Files.exists(current.resolve(GIT_DIRECTORY)) || Files.isRegularFile(ignoreFile);
            if (Files.isRegularFile(ignoreFile)) {
                read(ignoreFile, current);
            }
        }
        this.repository = found;
    }

    /**
     * @param root проверяемая папка
     */
    public static GitIgnore of(Path root) {
        return new GitIgnore(root.toAbsolutePath().normalize());
    }

    /**
     * @return true, если папка хранится в git: рядом или выше нашлись .git либо .gitignore
     */
    public boolean isRepository() {
        return repository;
    }

    /**
     * @return true, если файл закрыт правилами .gitignore и в хранилище не попадает
     */
    public boolean isIgnored(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        boolean ignored = false;
        for (Rule rule : rules) {
            if (!absolute.startsWith(rule.base())) {
                continue;
            }
            String relative = rule.base().relativize(absolute).toString().replace('\\', '/');
            if (rule.pattern().matcher(relative).matches()) {
                ignored = !rule.negated();
            }
        }
        return ignored;
    }

    private void read(Path ignoreFile, Path base) {
        try (Stream<String> lines = Files.lines(ignoreFile)) {
            lines.map(String::trim)
                    // Пустые строки и комментарии шаблона не задают
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(line -> {
                        boolean negated = line.startsWith("!");
                        String glob = negated ? line.substring(1).trim() : line;
                        if (!OUTER_SLASHES.matcher(glob).replaceAll("").isEmpty()) {
                            rules.add(new Rule(base, Pattern.compile(toRegex(glob)), negated));
                        }
                    });
        } catch (IOException | UncheckedIOException exception) {
            // Не прочитался - считаем, что правил в нем нет
        }
    }

    // *.log, logs/, /dump.hprof, docs/build, **/*.db
    private String toRegex(String line) {
        boolean directoryOnly = line.endsWith("/");
        String glob = OUTER_SLASHES.matcher(line).replaceAll("");
        // Косая черта в начале или в середине привязывает шаблон к каталогу файла .gitignore
        boolean anchored = line.startsWith("/") || glob.contains("/") && !glob.startsWith("**/");
        StringBuilder regex = new StringBuilder(anchored ? "" : "(.*/)?");
        for (int index = 0; index < glob.length(); index++) {
            char symbol = glob.charAt(index);
            if (symbol == '*' && glob.startsWith("**/", index)) {
                regex.append("(.*/)?");
                index += 2;
            } else if (symbol == '*') {
                regex.append(glob.startsWith("**", index) ? ".*" : "[^/]*");
            } else if (symbol == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(symbol)));
            }
        }
        // Каталог закрывает все, что в нем лежит; шаблон с косой чертой на конце относится только к каталогам
        return regex + (directoryOnly ? "/.*" : "(/.*)?");
    }
}
