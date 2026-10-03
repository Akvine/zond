package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckIntegerDivisionRuleTest {
    private final CheckIntegerDivisionRule rule = new CheckIntegerDivisionRule();

    @Test
    void findsIntegerDivisionStoredAsFloatingPoint() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private double ratio;
                    double run(int a, int b, long total, List<String> items, double rate) {
                        double r1 = a / b;
                        double r2 = total / items.size() * 100;
                        float r3 = 5 / 2;
                        this.ratio = a / b;
                        double ok1 = (double) a / b;
                        double ok2 = a / rate;
                        double ok3 = a / 2.0;
                        int ok4 = a / b;
                        double ok5 = Math.round(a / b);
                        return a / b;
                    }
                }
                """)).containsExactly(4, 5, 6, 7, 13);
    }
}
