package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.logical.CheckFloatingPointEqualityRule;

import static org.assertj.core.api.Assertions.assertThat;

class CheckFloatingPointEqualityRuleTest {
    private final CheckFloatingPointEqualityRule rule = new CheckFloatingPointEqualityRule();

    @Test
    void findsFloatingPointComparedForEquality() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private double total;
                    boolean run(double a, float b, Double boxed, int count) {
                        boolean r1 = a == 0.1;
                        boolean r2 = b != a;
                        boolean r3 = this.total == count;
                        boolean r4 = boxed == 1.5f;
                        boolean ok1 = count == 5;
                        boolean ok2 = a != a;
                        boolean ok3 = Math.abs(a - b) < 0.0001;
                        boolean ok4 = boxed == null;
                        return r1;
                    }
                }
                """)).containsExactly(4, 5, 6, 7);
    }
}
