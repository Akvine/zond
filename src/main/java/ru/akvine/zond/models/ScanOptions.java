package ru.akvine.zond.models;

import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.DurationUnit;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.FileKind;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Что именно проверять при сканировании
 *
 * @param disabledRules коды и имена отключенных правил в нижнем регистре: jr:40, checkmagicnumberrule
 * @param minLevel      наименее строгий уровень, который еще попадает в отчет
 * @param skipTests     не проверять файлы из каталогов test
 * @param classpath     jar-файлы и папки с библиотеками проекта: по ним разрешаются типы из зависимостей
 * @param exclusions    пути, которые сканировать не нужно
 * @param threads       сколько потоков использовать при сканировании: 1 - один, 0 - по числу ядер
 * @param minConfidence наименьшая уверенность находки, с которой она еще попадает в отчет
 * @param skippedKinds  виды файлов помимо Java, которые проверять не нужно: SQL, файлы сборки и прочие
 * @param timeUnit      в чем показывать время работы правил
 * @param testRulesOnly в тестовом коде работают только правила для тестов
 * @param changedFiles  файлы, находки в которых нужно показать; null - весь проект
 */
public record ScanOptions(
        Set<String> disabledRules,
        ErrorLevel minLevel,
        boolean skipTests,
        List<Path> classpath,
        PathExclusions exclusions,
        int threads,
        Set<FileKind> skippedKinds,
        Confidence minConfidence,
        DurationUnit timeUnit,
        boolean testRulesOnly,
        Set<Path> changedFiles) {
    private static final int SINGLE_THREAD = 1;
    private static final String SEPARATOR = "[,;\\s]+";
    private static final String TEST_DIRECTORY = "test";
    private static final String SOURCE_DIRECTORY = "src";
    private static final Set<String> BUILD_DIRECTORIES = Set.of("build", "target", "out");
    private static final String OLD_NAME_PREFIX = "Check";

    /**
     * @return настройки по умолчанию: все правила, все уровни, все файлы
     */
    public static ScanOptions defaults() {
        return new ScanOptions(
                Set.of(), ErrorLevel.INFO, false, List.of(), PathExclusions.none(), SINGLE_THREAD, Set.of(), Confidence.SUSPICION, DurationUnit.MILLISECONDS, true, null);
    }

    public ScanOptions withSkipTests(boolean skip) {
        return new ScanOptions(disabledRules, minLevel, skip, classpath, exclusions, threads, skippedKinds, minConfidence, timeUnit, testRulesOnly, changedFiles);
    }

    public ScanOptions withClasspath(List<Path> libraries) {
        return new ScanOptions(
                disabledRules, minLevel, skipTests, List.copyOf(libraries), exclusions, threads, skippedKinds, minConfidence, timeUnit, testRulesOnly, changedFiles);
    }

    public ScanOptions withExclusions(PathExclusions excluded) {
        return new ScanOptions(disabledRules, minLevel, skipTests, classpath, excluded, threads, skippedKinds, minConfidence, timeUnit, testRulesOnly, changedFiles);
    }

    public ScanOptions withThreads(int count) {
        return new ScanOptions(disabledRules, minLevel, skipTests, classpath, exclusions, count, skippedKinds, minConfidence, timeUnit, testRulesOnly, changedFiles);
    }

    public ScanOptions withMinConfidence(Confidence confidence) {
        return new ScanOptions(
                disabledRules, minLevel, skipTests, classpath, exclusions, threads, skippedKinds, confidence, timeUnit, testRulesOnly, changedFiles);
    }

    public ScanOptions withTimeUnit(DurationUnit unit) {
        return new ScanOptions(disabledRules, minLevel, skipTests, classpath, exclusions, threads, skippedKinds, minConfidence, unit, testRulesOnly, changedFiles);
    }

    public ScanOptions withTestRulesOnly(boolean only) {
        return new ScanOptions(
                disabledRules, minLevel, skipTests, classpath, exclusions, threads, skippedKinds, minConfidence, timeUnit, only, changedFiles);
    }

    /**
     * @param files измененные файлы с абсолютными путями; null - проверять весь проект
     */
    public ScanOptions withChangedFiles(Set<Path> files) {
        return new ScanOptions(
                disabledRules, minLevel, skipTests, classpath, exclusions, threads, skippedKinds, minConfidence, timeUnit,
                testRulesOnly, files == null ? null : Set.copyOf(files));
    }

    /**
     * @return true, если показывать нужно находки только в измененных файлах
     */
    public boolean changedOnly() {
        return changedFiles != null;
    }

    /**
     * @return true, если файл изменен либо проверяется весь проект
     */
    public boolean isChanged(Path file) {
        return changedFiles == null || changedFiles.contains(file.toAbsolutePath().normalize());
    }

    public ScanOptions withSkippedKinds(Set<FileKind> kinds) {
        return new ScanOptions(disabledRules, minLevel, skipTests, classpath, exclusions, threads, Set.copyOf(kinds), minConfidence, timeUnit, testRulesOnly, changedFiles);
    }

    /**
     * @return сколько потоков использовать: 1 - все по очереди, 0 - по числу ядер процессора
     */
    public int threadCount() {
        return threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads;
    }

    /**
     * @param root с чего начато сканирование
     * @return true, если файл нужно проверять
     */
    public boolean includes(Path root, Path file) {
        if (exclusions.matches(root, file)) {
            return false;
        }
        if (!skipTests) {
            return true;
        }

        return !isTestFile(root, file);
    }

    /**
     * @return true для файла из каталога сборки (build, target, out): копии исходников, которые туда кладут
     * форматтеры и генераторы, и сгенерированный код. Каталог с таким именем внутри src - обычный пакет
     */
    public boolean isBuildOutput(Path root, Path file) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        Path relative = absoluteFile.startsWith(absoluteRoot) ? absoluteRoot.relativize(absoluteFile) : file;
        for (Path part : relative) {
            if (SOURCE_DIRECTORY.equals(part.toString())) {
                return false;
            }
            if (BUILD_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true для файла из каталога test: тестовый код и его ресурсы
     */
    public boolean isTestFile(Path root, Path file) {
        // Смотрим только на часть пути ниже корня: если сканировать попросили сам каталог test, его и проверяем
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        Path relative = absoluteFile.startsWith(absoluteRoot) ? absoluteRoot.relativize(absoluteFile) : file;
        for (Path part : relative) {
            if (TEST_DIRECTORY.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param disabledRules список правил через запятую: "jr:40, jr:41, TodoCommentRule"; может быть пустым
     * @param minLevel      имя уровня: CRITICAL, MAJOR, MINOR, INFO; пустая строка - без порога
     */
    public static ScanOptions parse(String disabledRules, String minLevel) {
        Set<String> disabled = Arrays.stream(disabledRules.split(SEPARATOR))
                .filter(rule -> !rule.isBlank())
                .map(ScanOptions::normalize)
                .collect(Collectors.toSet());
        return new ScanOptions(
                disabled, parseLevel(minLevel), false, List.of(), PathExclusions.none(), SINGLE_THREAD, Set.of(),
                Confidence.SUSPICION, DurationUnit.MILLISECONDS, true, null);
    }

    /**
     * @return true, если файлы этого вида нужно проверять
     */
    public boolean scans(FileKind kind) {
        return !skippedKinds.contains(kind);
    }

    /**
     * @return true, если правило с такими кодом, именем и уровнем нужно запускать
     */
    public boolean allows(String ruleCode, String ruleName, ErrorLevel level) {
        // Уровни в ErrorLevel объявлены от самого строгого к самому мягкому
        return level.ordinal() <= minLevel.ordinal()
                && !disabledRules.contains(normalize(ruleCode))
                && !disabledRules.contains(normalize(ruleName))
                // Имя из настроек, написанных до переименования правил: CheckTodoCommentRule
                && !disabledRules.contains(normalize(OLD_NAME_PREFIX + ruleName));
    }

    private static ErrorLevel parseLevel(String level) {
        if (level.isBlank()) {
            return ErrorLevel.INFO;
        }
        try {
            return ErrorLevel.parse(level);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Неизвестный уровень '" + level.trim() + "'. Допустимые значения: "
                    + Arrays.stream(ErrorLevel.values()).map(Enum::name).collect(Collectors.joining(", ")));
        }
    }

    private static String normalize(String rule) {
        return rule.trim().toLowerCase(Locale.ROOT);
    }
}
