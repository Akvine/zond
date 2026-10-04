package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.RuleInfo;
import ru.akvine.zond.models.ScanOptions;
import ru.akvine.zond.rules.codesmell.CheckTooManyParametersRule;
import ru.akvine.zond.services.RuleCatalog;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleParametersTest {
    private static final String THREE_PARAMETERS = """
            class Sample {
                void run(int first, int second, int third) {}
            }
            """;

    @Test
    void thresholdComesFromSettings() {
        CheckTooManyParametersRule rule = new CheckTooManyParametersRule();
        assertThat(RuleTests.lines(rule, THREE_PARAMETERS)).isEmpty();

        rule.setSettings(RuleSettings.of(Map.of("jr-104.max-parameters", "2")));
        assertThat(RuleTests.check(rule, THREE_PARAMETERS))
                .singleElement()
                .satisfies(violation -> assertThat(violation.message()).contains("3 параметров при допустимых 2"));
    }

    @Test
    void ruleIsFoundByCodeInAnyFormAndByName() {
        for (String key : List.of("jr-104", "JR_104", "jr104", "jr:104", "CheckTooManyParametersRule")) {
            CheckTooManyParametersRule rule = new CheckTooManyParametersRule();
            rule.setSettings(RuleSettings.of(Map.of(key + ".max-parameters", "2")));

            assertThat(RuleTests.lines(rule, THREE_PARAMETERS)).as(key).containsExactly(2);
        }
    }

    @Test
    void settingsOfOtherRuleAreIgnored() {
        CheckTooManyParametersRule rule = new CheckTooManyParametersRule();
        rule.setSettings(RuleSettings.of(Map.of("jr-1.max-parameters", "2")));

        assertThat(RuleTests.lines(rule, THREE_PARAMETERS)).isEmpty();
    }

    @Test
    void catalogShowsEffectiveLevelAndParameters() {
        RuleSettings settings = RuleSettings.of(Map.of(
                "jr-104.max-parameters", "6",
                "jr-104.level", "major"));
        CheckTooManyParametersRule rule = new CheckTooManyParametersRule();
        rule.setSettings(settings);

        RuleInfo info = new RuleCatalog(List.of(rule), settings).describe(ScanOptions.defaults()).get(0);

        assertThat(info.level()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(info.description()).contains("больше 6 параметров");
        assertThat(info.settings()).hasSize(2);
        assertThat(info.settings().get(0)).startsWith("zond.rule.jr-104.level=MAJOR (по умолчанию ");
        assertThat(info.settings().get(1)).startsWith("zond.rule.jr-104.max-parameters=6 (по умолчанию 4)");
    }

    @Test
    void catalogShowsDefaultsWithoutSettings() {
        RuleInfo info = catalog(Map.of()).describe(ScanOptions.defaults()).get(0);

        assertThat(info.settings())
                .singleElement()
                .satisfies(setting -> assertThat(setting).startsWith("zond.rule.jr-104.max-parameters=4 - "));
    }

    @Test
    void correctSettingsHaveNoProblems() {
        assertThat(catalog(Map.of("jr-104.max-parameters", "6", "CheckTooManyParametersRule.level", "INFO"))
                .findSettingsProblems()).isEmpty();
    }

    @Test
    void mistakesInSettingsAreReported() {
        assertThat(catalog(Map.of("jr-999.level", "INFO")).findSettingsProblems())
                .singleElement().asString().contains("Правило 'jr-999'").contains("не найдено");
        assertThat(catalog(Map.of("jr-104.max-params", "6")).findSettingsProblems())
                .singleElement().asString().contains("нет параметра 'max-params'").contains("max-parameters, level");
        assertThat(catalog(Map.of("jr-104.max-parameters", "many")).findSettingsProblems())
                .singleElement().asString().contains("целым неотрицательным числом");
        assertThat(catalog(Map.of("jr-104.max-parameters", "-1")).findSettingsProblems())
                .singleElement().asString().contains("целым неотрицательным числом");
        assertThat(catalog(Map.of("jr-104.level", "HIGH")).findSettingsProblems())
                .singleElement().asString().contains("Неизвестный уровень 'HIGH'");
        // jr:104 в ключе .properties: двоеточие отделило значение, от ключа осталось zond.rule.jr
        assertThat(catalog(Map.of("jr", "104.level=INFO")).findSettingsProblems())
                .singleElement().asString().contains("Непонятная настройка 'zond.rule.jr'");
    }

    private RuleCatalog catalog(Map<String, String> properties) {
        return new RuleCatalog(List.of(new CheckTooManyParametersRule()), RuleSettings.of(properties));
    }
}
