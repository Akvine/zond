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
 * zond.rule.LongMethodRule.max-lines=80
 * zond.rule.jr-36.level=INFO
 * </pre>
 */
@Component
public class RuleSettings {
    public static final String PREFIX = "zond.rule.";
    public static final String LEVEL = "level";
    public static final String OLD_NAME_PREFIX = "Check";

    /**
     * Параметр, который можно задать сразу для всех правил, у которых он есть: zond.max-call-depth=5.
     * Значение для отдельного правила (zond.rule.jr-57.max-call-depth) важнее общего
     */
    public static final String MAX_CALL_DEPTH = "max-call-depth";
    public static final String COMMON_PREFIX = "zond.";

    private static final char SEPARATOR = '.';
    private static final String CODE_PUNCTUATION = "[:_-]";

    // Правило (в том виде, как записано в настройках) -> параметр -> значение
    private final Map<String, Map<String, String>> configured = new LinkedHashMap<>();
    private final List<String> malformedKeys = new ArrayList<>();
    // Параметр -> значение, общее для всех правил
    private final Map<String, String> common = new LinkedHashMap<>();

    @Autowired
    public RuleSettings(ConfigurableEnvironment environment) {
        this(read(environment));
        String depth = environment.getProperty(COMMON_PREFIX + MAX_CALL_DEPTH);
        if (depth != null && !depth.isBlank()) {
            common.put(MAX_CALL_DEPTH, depth.trim());
        }
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
     * @param properties настройки правил без общего префикса
     * @param common     параметры, общие для всех правил: "max-call-depth" -> "5"
     */
    public static RuleSettings of(Map<String, String> properties, Map<String, String> common) {
        RuleSettings settings = new RuleSettings(properties);
        settings.common.putAll(common);
        return settings;
    }

    /**
     * @return значение параметра из настроек либо значение по умолчанию
     * @throws IllegalArgumentException если в настройках не целое неотрицательное число
     */
    public int value(String ruleCode, String ruleName, RuleParameter parameter) {
        Optional<String> own = find(ruleCode, ruleName, parameter.name());
        // Своей настройки у правила нет - действует общая, если она задана для этого параметра
        Optional<String> value = own.or(() -> Optional.ofNullable(common.get(parameter.name())));
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
        String setting = own.isPresent()
                ? "Параметр '" + parameter.name() + "' правила " + ruleCode + " должен"
                : "Настройка '" + COMMON_PREFIX + parameter.name() + "' должна";
        throw new IllegalArgumentException(setting + " быть целым неотрицательным числом, а задано '"
                + value.get() + "'");
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
     * @return параметры, заданные сразу для всех правил: параметр -> значение
     */
    public Map<String, String> common() {
        return Collections.unmodifiableMap(common);
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
        return normalize(configuredRule).equals(normalize(ruleCode)) || isNameOf(configuredRule, ruleName);
    }

    /**
     * @return true, если правило названо этим именем. Раньше имена правил начинались с Check
     * (CheckLongMethodRule): в настройках, написанных до переименования, старое имя продолжает работать
     */
    public static boolean isNameOf(String given, String ruleName) {
        String name = given.trim();
        return name.equalsIgnoreCase(ruleName) || name.equalsIgnoreCase(OLD_NAME_PREFIX + ruleName);
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
