package ru.akvine.zond.config;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.codesmell.LongMethodRule;
import ru.akvine.zond.services.RuleCatalog;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Раньше имена правил начинались с Check. Настройки, написанные до переименования, продолжают работать
 */
class OldRuleNamesTest {
    private static final RuleParameter MAX_LINES = new RuleParameter("max-lines", 50, "");

    @Test
    void ruleSettingsAcceptBothNames() {
        assertThat(RuleSettings.of(Map.of("LongMethodRule.max-lines", "80")).value("jr:112", "LongMethodRule", MAX_LINES))
                .isEqualTo(80);
        assertThat(RuleSettings.of(Map.of("CheckLongMethodRule.max-lines", "70")).value("jr:112", "LongMethodRule", MAX_LINES))
                .isEqualTo(70);
    }

    @Test
    void disabledRulesAcceptBothNames() {
        assertThat(ScanOptions.parse("LongMethodRule", "").allows("jr:112", "LongMethodRule", ErrorLevel.MINOR)).isFalse();
        assertThat(ScanOptions.parse("CheckLongMethodRule", "").allows("jr:112", "LongMethodRule", ErrorLevel.MINOR)).isFalse();
        assertThat(ScanOptions.parse("CheckOtherRule", "").allows("jr:112", "LongMethodRule", ErrorLevel.MINOR)).isTrue();
    }

    @Test
    void catalogFindsRuleByOldNameAndReportsNoProblems() {
        List<Rule> rules = List.of(new LongMethodRule());
        RuleCatalog catalog = new RuleCatalog(rules, RuleSettings.of(Map.of("CheckLongMethodRule.max-lines", "70")));

        assertThat(catalog.findCode("CheckLongMethodRule")).isEqualTo(catalog.findCode("LongMethodRule")).isPresent();
        assertThat(catalog.findSettingsProblems()).isEmpty();
    }
}
