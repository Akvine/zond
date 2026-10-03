package ru.akvine.zond.printers;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.RuleInfo;

import java.util.List;

/**
 * Список правил в виде текста: для консоли и текстового файла
 */
@Component
public class RuleListFormatter {
    private static final String LINE_SEPARATOR = System.lineSeparator();
    private static final String DESCRIPTION_INDENT = "    ";
    private static final String DISABLED_MARK = "  [отключено]";
    private static final String NO_DESCRIPTION = "(описание не задано)";

    public String format(List<RuleInfo> rules) {
        long active = rules.stream().filter(RuleInfo::active).count();

        StringBuilder text = new StringBuilder();
        text.append("Zond: список правил").append(LINE_SEPARATOR);
        text.append("Всего правил: ").append(rules.size())
                .append(", активно: ").append(active)
                .append(", отключено: ").append(rules.size() - active)
                .append(LINE_SEPARATOR);

        // Самые строгие уровни - первыми; внутри уровня правила идут по коду
        for (ErrorLevel level : ErrorLevel.values()) {
            List<RuleInfo> ofLevel = rules.stream().filter(rule -> rule.level() == level).toList();
            if (ofLevel.isEmpty()) {
                continue;
            }

            text.append(LINE_SEPARATOR)
                    .append("=== ").append(level).append(" (").append(ofLevel.size()).append(") ===")
                    .append(LINE_SEPARATOR);
            for (RuleInfo rule : ofLevel) {
                text.append(rule.code()).append("  ").append(rule.name())
                        .append(rule.active() ? "" : DISABLED_MARK)
                        .append(LINE_SEPARATOR);
                text.append(DESCRIPTION_INDENT).append(describe(rule)).append(LINE_SEPARATOR);
            }
        }
        return text.toString();
    }

    private String describe(RuleInfo rule) {
        return rule.description() == null || rule.description().isBlank() ? NO_DESCRIPTION : rule.description();
    }
}
