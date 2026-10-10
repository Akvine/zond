package ru.akvine.zond.loaders;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Файлы, которые изменились в рабочей копии git: по ним проверка показывает находки, когда просят
 * проверять только измененное. Изменения берутся у самого git - он знает их точнее, чем даты файлов.
 */
@Component
public class GitChangedFiles {
    public static final String HEAD = "HEAD";

    private static final String GIT = "git";
    private static final String NAMES_SEPARATOR = "\0";
    private static final long TIMEOUT_SECONDS = 60;

    // Ветка, тег, коммит либо выражение вроде HEAD~3 и origin/main. Начинаться с дефиса имя не может:
    // иначе git принял бы его за свой параметр
    private static final Pattern REFERENCE = Pattern.compile("[\\w./@^~{}][\\w./@^~{}\\-]*");

    /**
     * @param files измененные файлы: абсолютные пути без точек и повторов
     * @param base  с чем сравнивали: HEAD либо заданная ветка
     */
    public record Changes(Set<Path> files, String base) {
    }

    /**
     * @param reference ветка, тег или коммит как их ввели; пустая строка - сравнение с HEAD
     * @return то же имя без пробелов по краям
     * @throws IllegalArgumentException если имя не похоже на то, что понимает git
     */
    public static String validate(String reference) {
        String value = reference == null ? "" : reference.trim();
        if (!value.isEmpty() && !REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException("'" + value + "' не похоже на ветку, тег или коммит git:"
                    + " допустимы буквы, цифры и знаки . / _ - @ ^ ~, например main, origin/main, v1.2.0, HEAD~3");
        }
        return value;
    }

    /**
     * Что изменилось по сравнению с точкой отсчета: закоммиченное после нее, незакоммиченное и новые файлы,
     * которые git еще не отслеживает. Удаленные файлы не входят - проверять в них нечего.
     *
     * @param target что сканируют: изменения берутся только внутри этой папки
     * @param since  точка отсчета; пустая строка - HEAD, то есть только незакоммиченное
     * @return пусто, если папка не лежит в репозитории git либо git не установлен
     * @throws IllegalArgumentException если git не знает такой ветки или коммита
     */
    public Optional<Changes> find(Path target, String since) {
        Path directory = Files.isDirectory(target) ? target : target.toAbsolutePath().getParent();
        boolean insideRepository = run(directory, "rev-parse", "--is-inside-work-tree")
                .filter(output -> Boolean.parseBoolean(output.trim()))
                .isPresent();
        if (!insideRepository) {
            return Optional.empty();
        }

        String reference = validate(since).isEmpty() ? HEAD : validate(since);
        Set<Path> files = new LinkedHashSet<>();
        Optional<String> base = baseCommit(directory, reference);
        if (base.isPresent()) {
            // Рабочая копия против точки отсчета: сюда попадает и закоммиченное после нее, и незакоммиченное
            String changed = run(directory, "diff", "--name-only", "-z", "--relative", "--diff-filter=d", base.get())
                    .orElseThrow(() -> new IllegalStateException("git не смог сравнить рабочую копию с '" + reference + "'"));
            add(files, directory, changed);
        } else {
            // В репозитории еще нет ни одного коммита: сравнивать не с чем, изменено все
            run(directory, "ls-files", "-z").ifPresent(tracked -> add(files, directory, tracked));
        }
        run(directory, "ls-files", "--others", "--exclude-standard", "-z")
                .ifPresent(untracked -> add(files, directory, untracked));
        return Optional.of(new Changes(files, reference));
    }

    /**
     * @return коммит, с которым сравнивать; пусто, если в репозитории еще нет коммитов
     */
    private Optional<String> baseCommit(Path directory, String reference) {
        if (run(directory, "rev-parse", "--verify", "--quiet", HEAD + "^{commit}").isEmpty()) {
            return Optional.empty();
        }
        if (HEAD.equals(reference)) {
            return Optional.of(HEAD);
        }
        if (run(directory, "rev-parse", "--verify", "--quiet", reference + "^{commit}").isEmpty()) {
            throw new IllegalArgumentException("В репозитории нет ветки, тега или коммита '" + reference
                    + "': проверьте имя (для ветки с сервера - origin/имя)");
        }
        // Сравниваем с местом, где текущая ветка отошла от заданной: то, что с тех пор изменили в заданной
        // ветке другие, изменением текущей не считается
        return run(directory, "merge-base", reference, HEAD)
                .map(String::trim)
                .filter(commit -> !commit.isEmpty())
                .or(() -> Optional.of(reference));
    }

    private void add(Set<Path> files, Path directory, String names) {
        for (String name : names.split(NAMES_SEPARATOR)) {
            if (!name.isBlank()) {
                files.add(directory.resolve(name).toAbsolutePath().normalize());
            }
        }
    }

    /**
     * @return вывод команды; пусто, если git завершился с ошибкой, не ответил вовремя либо не установлен
     */
    private Optional<String> run(Path directory, String... arguments) {
        // core.quotepath=off: имена с кириллицей приходят как есть, а не кодами
        List<String> command = new ArrayList<>(List.of(GIT, "-c", "core.quotepath=off"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 ? Optional.of(new String(output, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
