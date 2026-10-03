package ru.akvine.zond.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.RuleInfo;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.Rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Справочник всех правил приложения
 */
@Service
@RequiredArgsConstructor
public class RuleCatalog {
    private static final String CODE_SEPARATOR = ":";

    /**
     * Spring отдает правила в произвольном порядке - выстраиваем по номеру кода: jr:1, jr:2, ... jr:10
     */
    public static final Comparator<Rule> BY_CODE = Comparator.comparingInt(rule -> codeNumber(rule.code()));

    private final List<Rule> rules;
    private final RuleSettings ruleSettings;

    /**
     * @param options текущие настройки: по ним определяется, какие правила сейчас не запускаются
     */
    public List<RuleInfo> describe(ScanOptions options) {
        return rules.stream()
                .sorted(BY_CODE)
                .map(rule -> {
                    ErrorLevel level = ruleSettings.level(rule.code(), rule.name()).orElseGet(rule::errorLevel);
                    return new RuleInfo(
                            rule.code(),
                            rule.name(),
                            level,
                            rule.errorType(),
                            rule.description(),
                            rule.enabled() && options.allows(rule.code(), rule.name(), level),
                            describeSettings(rule, level));
                })
                .toList();
    }

    /**
     * @param codeOrName код (jr:40) либо имя класса правила, без учета регистра
     * @return код найденного правила
     */
    public Optional<String> findCode(String codeOrName) {
        String wanted = codeOrName.trim();
        return rules.stream()
                .filter(rule -> rule.code().equalsIgnoreCase(wanted) || rule.name().equalsIgnoreCase(wanted))
                .map(Rule::code)
                .findFirst();
    }

    /**
     * Проверяет настройки zond.rule.*: опечатка в имени правила или параметра иначе осталась бы незамеченной,
     * а порог - прежним.
     *
     * @return описания ошибок; пусто, если настройки верны
     */
    public List<String> findSettingsProblems() {
        List<String> problems = new ArrayList<>();
        for (String key : ruleSettings.malformedKeys()) {
            problems.add("Непонятная настройка '" + key + "': ожидается " + RuleSettings.PREFIX
                    + "<правило>.<параметр>, где правило - имя класса или код через дефис (jr-193)");
        }

        for (Map.Entry<String, Map<String, String>> configured : ruleSettings.configured().entrySet()) {
            Optional<Rule> rule = rules.stream()
                    .filter(candidate -> RuleSettings.refersTo(configured.getKey(), candidate.code(), candidate.name()))
                    .findFirst();
            if (rule.isEmpty()) {
                problems.add("Правило '" + configured.getKey() + "' из настройки " + RuleSettings.PREFIX
                        + configured.getKey() + ".* не найдено: укажите имя класса или код через дефис (jr-193)");
                continue;
            }
            configured.getValue().keySet().forEach(parameter -> checkParameter(rule.get(), parameter, problems));
        }
        return problems;
    }

    private void checkParameter(Rule rule, String name, List<String> problems) {
        try {
            if (RuleSettings.LEVEL.equals(name)) {
                ruleSettings.level(rule.code(), rule.name());
                return;
            }

            Optional<RuleParameter> parameter = rule.parameters().stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst();
            if (parameter.isPresent()) {
                ruleSettings.value(rule.code(), rule.name(), parameter.get());
                return;
            }

            List<String> known = new ArrayList<>(rule.parameters().stream().map(RuleParameter::name).toList());
            known.add(RuleSettings.LEVEL);
            problems.add("У правила " + rule.code() + " (" + rule.name() + ") нет параметра '" + name
                    + "'. Доступные: " + String.join(", ", known));
        } catch (IllegalArgumentException exception) {
            problems.add(exception.getMessage());
        }
    }

    // Строки в том виде, в каком их можно перенести в app.properties: zond.rule.jr-193.max-complexity=10
    private List<String> describeSettings(Rule rule, ErrorLevel level) {
        String prefix = RuleSettings.PREFIX + RuleSettings.keyOf(rule.code()) + ".";
        List<String> settings = new ArrayList<>();
        if (level != rule.errorLevel()) {
            settings.add(prefix + RuleSettings.LEVEL + "=" + level + " (по умолчанию " + rule.errorLevel() + ")");
        }
        for (RuleParameter parameter : rule.parameters()) {
            int value = ruleSettings.value(rule.code(), rule.name(), parameter);
            settings.add(prefix + parameter.name() + "=" + value
                    + (value == parameter.defaultValue() ? "" : " (по умолчанию " + parameter.defaultValue() + ")")
                    + " - " + parameter.description());
        }
        return settings;
    }

    // jr:12 -> 12; код без номера уходит в конец
    private static int codeNumber(String code) {
        try {
            return Integer.parseInt(code.substring(code.lastIndexOf(CODE_SEPARATOR) + 1));
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }
}
