package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.rules.logical.CheckWriteInReadOnlyTransactionRule;
import ru.akvine.zond.rules.security.CheckSqlConcatenationRule;
import ru.akvine.zond.services.RuleCatalog;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Общая настройка zond.max-call-depth действует на все правила с таким параметром;
 * значение, заданное для отдельного правила, важнее
 */
class CommonCallDepthTest {
    private static final String DEPTH = "max-call-depth";

    // От параметра запроса до SQL-запроса пять вызовов: при глубине 4 путь обрывается
    private static final String CHAIN = """
            @RestController
            class Users {
                @GetMapping("/users")
                void find(@RequestParam String name) {
                    first(name);
                }
                void first(String value) {
                    second(value);
                }
                void second(String value) {
                    third(value);
                }
                void third(String value) {
                    fourth(value);
                }
                void fourth(String value) {
                    query(value);
                }
                void query(String value) {
                    jdbc.query("select * from users where name = '" + value + "'", mapper);
                }
            }
            """;

    @Test
    void commonDepthAppliesToEveryRuleWithThisParameter() {
        RuleSettings settings = RuleSettings.of(Map.of(), Map.of(DEPTH, "7"));

        assertThat(depthOf(new CheckSqlConcatenationRule(), settings)).isEqualTo(7);
        assertThat(depthOf(new CheckWriteInReadOnlyTransactionRule(), settings)).isEqualTo(7);
    }

    @Test
    void ruleOwnDepthIsMoreImportantThanCommon() {
        RuleSettings settings = RuleSettings.of(Map.of("jr-18." + DEPTH, "2"), Map.of(DEPTH, "7"));

        assertThat(depthOf(new CheckSqlConcatenationRule(), settings)).isEqualTo(2);
        assertThat(depthOf(new CheckWriteInReadOnlyTransactionRule(), settings)).isEqualTo(7);
    }

    @Test
    void defaultsStayWhenNothingIsConfigured() {
        assertThat(depthOf(new CheckSqlConcatenationRule(), RuleSettings.empty())).isEqualTo(4);
        assertThat(depthOf(new CheckWriteInReadOnlyTransactionRule(), RuleSettings.empty())).isEqualTo(3);
    }

    @Test
    void depthDecidesHowFarValueIsTraced() {
        assertThat(lines(RuleSettings.empty())).isEmpty();
        assertThat(lines(RuleSettings.of(Map.of(), Map.of(DEPTH, "6")))).containsExactly(20);
        assertThat(lines(RuleSettings.of(Map.of("jr-18." + DEPTH, "2"), Map.of(DEPTH, "6")))).isEmpty();
        assertThat(lines(RuleSettings.of(Map.of("CheckSqlConcatenationRule." + DEPTH, "6"), Map.of(DEPTH, "1"))))
                .containsExactly(20);
    }

    @Test
    void mistakeInCommonDepthIsReported() {
        List<Rule> rules = List.of(new CheckSqlConcatenationRule());

        assertThat(new RuleCatalog(rules, RuleSettings.of(Map.of(), Map.of(DEPTH, "много"))).findSettingsProblems())
                .singleElement()
                .satisfies(problem -> assertThat(problem).contains("zond.max-call-depth").contains("много"));
        assertThat(new RuleCatalog(rules, RuleSettings.of(Map.of(), Map.of(DEPTH, "5"))).findSettingsProblems())
                .isEmpty();
    }

    private int depthOf(AbstractRule rule, RuleSettings settings) {
        rule.setSettings(settings);
        RuleParameter parameter = rule.parameters().stream()
                .filter(candidate -> candidate.name().equals(DEPTH))
                .findFirst()
                .orElseThrow();
        return rule.value(parameter);
    }

    private List<Integer> lines(RuleSettings settings) {
        CheckSqlConcatenationRule rule = new CheckSqlConcatenationRule();
        rule.setSettings(settings);
        return RuleTests.lines(rule, CHAIN);
    }
}
