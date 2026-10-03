package ru.akvine.zond.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.RuleParameter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Настройки отдельных правил: zond.rule.&lt;правило&gt;.&lt;параметр&gt;=значение.
 * Правило задается именем класса либо кодом через дефис: в .properties двоеточие отделяет ключ от значения,
 * поэтому jr:193 в ключе записать нельзя.
 * <pre>
 * zond.rule.jr-193.max-complexity=15
 * zond.rule.CheckLongMethodRule.max-lines=80
 * zond.rule.jr-36.level=INFO
 * </pre>
 */
@Component
public class RuleSettings {
    public static final String PREFIX = "zond.rule.";
    public static final String LEVEL = "level";

    private static final char SEPARATOR = '.';
    private static final String CODE_PUNCTUATION = "[:_-]";

    // Правило (в том виде, как записано в настройках) -> параметр -> значение
    private final Map<String, Map<String, String>> configured = new LinkedHashMap<>();
    private final List<String> malformedKeys = new ArrayList<>();

    @Autowired
    public RuleSettings(ConfigurableEnvironment environment) {
        this(read(environment));
    }

    private RuleSettings(Map<String, String> properties) {
        properties.forEach((key, value) -> {
            int separator = key.lastIndexOf(SEPARATOR);
            if (separator <= 0 || separator == key.length() - 1) {
                malformedKeys.add(PREFIX + key);
                return;
            }
            configured.computeIfAbsent(key.substring(0, separator), rule -> new LinkedHashMap<>())
                    .put(key.substring(separator + 1).toLowerCase(Locale.ROOT), value.trim());
        });
    }

    public static RuleSettings empty() {
        return new RuleSettings(Map.of());
    }

    /**
     * @param properties настройки без общего префикса: "jr-193.max-complexity" -> "15"
     */
    public static RuleSettings of(Map<String, String> properties) {
        return new RuleSettings(properties);
    }

    /**
     * @return значение параметра из настроек либо значение по умолчанию
     * @throws IllegalArgumentException если в настройках не целое неотрицательное число
     */
    public int value(String ruleCode, String ruleName, RuleParameter parameter) {
        Optional<String> value = find(ruleCode, ruleName, parameter.name());
        if (value.isEmpty()) {
            return parameter.defaultValue();
        }
        try {
            int number = Integer.parseInt(value.get());
            if (number >= 0) {
                return number;
            }
        } catch (NumberFormatException exception) {
            // Сообщение ниже общее для "не число" и "отрицательное число"
        }
        throw new IllegalArgumentException("Параметр '" + parameter.name() + "' правила " + ruleCode
                + " должен быть целым неотрицательным числом, а задано '" + value.get() + "'");
    }

    /**
     * @return уровень правила, если он переопределен в настройках
     * @throws IllegalArgumentException если уровень задан неверно
     */
    public Optional<ErrorLevel> level(String ruleCode, String ruleName) {
        Optional<String> value = find(ruleCode, ruleName, LEVEL);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ErrorLevel.valueOf(value.get().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Неизвестный уровень '" + value.get() + "' у правила " + ruleCode
                    + ". Допустимые значения: "
                    + Arrays.stream(ErrorLevel.values()).map(Enum::name).collect(Collectors.joining(", ")));
        }
    }

    /**
     * @return все заданные настройки: правило (как записано) -> параметр -> значение. Для проверки на опечатки
     */
    public Map<String, Map<String, String>> configured() {
        return Collections.unmodifiableMap(configured);
    }

    /**
     * @return ключи, в которых нет правила либо параметра: zond.rule.max-lines
     */
    public List<String> malformedKeys() {
        return List.copyOf(malformedKeys);
    }

    /**
     * @return true, если правило с таким кодом и именем записано в настройках как configuredRule
     */
    public static boolean refersTo(String configuredRule, String ruleCode, String ruleName) {
        return normalize(configuredRule).equals(normalize(ruleCode)) || configuredRule.equalsIgnoreCase(ruleName);
    }

    /**
     * @return как записать код правила в ключе настройки: jr:193 -> jr-193
     */
    public static String keyOf(String ruleCode) {
        return ruleCode.replace(':', '-');
    }

    private Optional<String> find(String ruleCode, String ruleName, String parameter) {
        return configured.entrySet().stream()
                .filter(rule -> refersTo(rule.getKey(), ruleCode, ruleName))
                .map(rule -> rule.getValue().get(parameter))
                .filter(value -> value != null)
                .findFirst();
    }

    // jr:193, jr-193, JR_193 -> jr193
    private static String normalize(String rule) {
        return rule.toLowerCase(Locale.ROOT).replaceAll(CODE_PUNCTUATION, "");
    }

    // Источники идут от самого важного (аргументы командной строки) к наименее важному - первое значение побеждает
    private static Map<String, String> read(ConfigurableEnvironment environment) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (name.startsWith(PREFIX)) {
                    String value = String.valueOf(enumerable.getProperty(name));
                    properties.putIfAbsent(name.substring(PREFIX.length()), value);
                }
            }
        }
        return properties;
    }
}
