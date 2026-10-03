package ru.akvine.zond.models;

import ru.akvine.zond.enums.ErrorLevel;

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
 */
public record ScanOptions(Set<String> disabledRules, ErrorLevel minLevel, boolean skipTests, List<Path> classpath) {
    private static final String SEPARATOR = "[,;\\s]+";
    private static final String TEST_DIRECTORY = "test";

    /**
     * @return настройки по умолчанию: все правила, все уровни, все файлы
     */
    public static ScanOptions defaults() {
        return new ScanOptions(Set.of(), ErrorLevel.INFO, false, List.of());
    }

    public ScanOptions withSkipTests(boolean skip) {
        return new ScanOptions(disabledRules, minLevel, skip, classpath);
    }

    public ScanOptions withClasspath(List<Path> libraries) {
        return new ScanOptions(disabledRules, minLevel, skipTests, List.copyOf(libraries));
    }

    /**
     * @param root с чего начато сканирование
     * @return true, если файл нужно проверять
     */
    public boolean includes(Path root, Path file) {
        if (!skipTests) {
            return true;
        }

        // Смотрим только на часть пути ниже корня: если сканировать попросили сам каталог test, его и проверяем
        Path relative = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        for (Path part : relative) {
            if (TEST_DIRECTORY.equals(part.toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param disabledRules список правил через запятую: "jr:40, jr:41, CheckTodoCommentRule"; может быть пустым
     * @param minLevel      имя уровня: BLOCKER, CRITICAL, MAJOR, MINOR, INFO; пустая строка - без порога
     */
    public static ScanOptions parse(String disabledRules, String minLevel) {
        Set<String> disabled = Arrays.stream(disabledRules.split(SEPARATOR))
                .filter(rule -> !rule.isBlank())
                .map(ScanOptions::normalize)
                .collect(Collectors.toSet());
        return new ScanOptions(disabled, parseLevel(minLevel), false, List.of());
    }

    /**
     * @return true, если правило с такими кодом, именем и уровнем нужно запускать
     */
    public boolean allows(String ruleCode, String ruleName, ErrorLevel level) {
        // Уровни в ErrorLevel объявлены от самого строгого к самому мягкому
        return level.ordinal() <= minLevel.ordinal()
                && !disabledRules.contains(normalize(ruleCode))
                && !disabledRules.contains(normalize(ruleName));
    }

    private static ErrorLevel parseLevel(String level) {
        if (level.isBlank()) {
            return ErrorLevel.INFO;
        }
        try {
            return ErrorLevel.valueOf(level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Неизвестный уровень '" + level.trim() + "'. Допустимые значения: "
                    + Arrays.stream(ErrorLevel.values()).map(Enum::name).collect(Collectors.joining(", ")));
        }
    }

    private static String normalize(String rule) {
        return rule.trim().toLowerCase(Locale.ROOT);
    }
}
