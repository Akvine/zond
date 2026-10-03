package ru.akvine.zond.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.akvine.zond.models.RuleInfo;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.Rule;

import java.util.Comparator;
import java.util.List;
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

    /**
     * @param options текущие настройки: по ним определяется, какие правила сейчас не запускаются
     */
    public List<RuleInfo> describe(ScanOptions options) {
        return rules.stream()
                .sorted(BY_CODE)
                .map(rule -> new RuleInfo(
                        rule.code(),
                        rule.name(),
                        rule.errorLevel(),
                        rule.errorType(),
                        rule.description(),
                        rule.enabled() && options.allows(rule.code(), rule.name(), rule.errorLevel())))
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

    // jr:12 -> 12; код без номера уходит в конец
    private static int codeNumber(String code) {
        try {
            return Integer.parseInt(code.substring(code.lastIndexOf(CODE_SEPARATOR) + 1));
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }
}
