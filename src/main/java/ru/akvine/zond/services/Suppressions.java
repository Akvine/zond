package ru.akvine.zond.services;

import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подавление находок комментариями в проверяемом коде:
 * <pre>
 *   int port = 8080; // zond:ignore jr:40        - на этой строке
 *   // zond:ignore jr:40, jr:104                 - на следующей строке
 *   // zond:ignore                               - все правила на следующей строке
 *   // zond:ignore-file jr:40                    - во всем файле
 * </pre>
 * В файлах настроек то же самое пишется после #.
 */
class Suppressions {
    // Директива должна стоять в комментарии: то же слово внутри строкового литерала подавлением не считается
    private static final Pattern DIRECTIVE =
            Pattern.compile("(?://|/\\*|^\\s*\\*|#).*?zond:ignore(-file)?\\b(.*)$");

    // jr:40 либо имя правила: CheckMagicNumberRule
    private static final Pattern RULE_ID = Pattern.compile("[A-Za-z]+:\\d+|Check\\w+Rule");

    // Строка, на которой нет ничего, кроме комментария
    private static final Pattern COMMENT_ONLY_LINE = Pattern.compile("^\\s*(//|/\\*|\\*|#).*");

    // Директива без списка правил относится ко всем правилам
    private static final String ALL_RULES = "*";

    /**
     * @param wholeFile правила, подавленные во всем файле
     * @param byLine    правила, подавленные на отдельных строках
     */
    private record FileSuppressions(Set<String> wholeFile, Map<Integer, Set<String>> byLine) {
    }

    private final Map<Path, FileSuppressions> cache = new HashMap<>();

    boolean isSuppressed(Violation violation) {
        FileSuppressions suppressions = cache.computeIfAbsent(violation.file(), this::read);
        return matches(suppressions.wholeFile(), violation)
                || matches(suppressions.byLine().getOrDefault(violation.line(), Set.of()), violation);
    }

    private boolean matches(Set<String> rules, Violation violation) {
        return rules.contains(ALL_RULES)
                || rules.contains(violation.ruleCode())
                || rules.contains(violation.ruleName());
    }

    private FileSuppressions read(Path file) {
        Set<String> wholeFile = new HashSet<>();
        Map<Integer, Set<String>> byLine = new HashMap<>();

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            // Файл не прочитался - значит, подавлений в нем нет
            return new FileSuppressions(wholeFile, byLine);
        }

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher directive = DIRECTIVE.matcher(line);
            if (!directive.find()) {
                continue;
            }

            Set<String> rules = parseRules(directive.group(2));
            if (directive.group(1) != null) {
                wholeFile.addAll(rules);
                continue;
            }

            // Комментарий в конце строки относится к ней самой, комментарий на отдельной строке - к следующей
            int number = index + 1;
            byLine.computeIfAbsent(number, key -> new HashSet<>()).addAll(rules);
            if (COMMENT_ONLY_LINE.matcher(line).matches()) {
                byLine.computeIfAbsent(number + 1, key -> new HashSet<>()).addAll(rules);
            }
        }
        return new FileSuppressions(wholeFile, byLine);
    }

    // После директивы может идти пояснение - правилами считаем только то, что на них похоже
    private Set<String> parseRules(String text) {
        Set<String> rules = new HashSet<>();
        Matcher matcher = RULE_ID.matcher(text);
        while (matcher.find()) {
            rules.add(matcher.group());
        }
        return rules.isEmpty() ? Set.of(ALL_RULES) : rules;
    }
}
