package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.logical.FloatingPointEqualityRule;

import static org.assertj.core.api.Assertions.assertThat;

class FloatingPointEqualityRuleTest {
    private final FloatingPointEqualityRule rule = new FloatingPointEqualityRule();

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
                        boolean ok5 = a == 0 || b != 0.0;
                        return r1;
                    }
                }
                """)).containsExactly(4, 5, 6, 7);
    }

    @Test
    void exactComparisonsInNumericCode() {
        assertThat(RuleTests.lines(rule, """
                class FastMath {
                    private double epsilon;
                    static double pow(double x, double y, RealVector v) {
                        if (x == Double.POSITIVE_INFINITY || v.getEntry(0) == ZERO) {
                            return x;
                        }
                        long longValue = (long) y;
                        if (longValue == y && Math.floor(x) == x) {
                            return 1;
                        }
                        return x == y ? 1 : x * y;
                    }
                    public boolean equals(Object other) {
                        return other instanceof FastMath rhs && epsilon == rhs.epsilon;
                    }
                }
                """)).containsExactly(11);
    }
}
