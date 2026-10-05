package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.EqualsHashCodeRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EqualsHashCodeRuleTest {
    private final EqualsHashCodeRule rule = new EqualsHashCodeRule();

    @Test
    void findsClassesWithOnlyOneOfTheMethods() {
        List<Violation> violations = RuleTests.check(rule, """
                class OnlyEquals {
                    @Override
                    public boolean equals(Object other) { return true; }
                }
                class OnlyHashCode {
                    public int hashCode() { return 1; }
                }
                class Both {
                    public boolean equals(Object other) { return true; }
                    public int hashCode() { return 1; }
                }
                @EqualsAndHashCode
                class WithLombok {
                    public boolean equals(Object other) { return true; }
                }
                class Overload {
                    public boolean equals(Overload other, int precision) { return true; }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(2, 6);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:15"));
        assertThat(violations.get(0).message()).contains("'OnlyEquals'", "equals без hashCode");
        assertThat(violations.get(1).message()).contains("'OnlyHashCode'", "hashCode без equals");
    }
}
