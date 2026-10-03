package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckBigDecimalMisuseRuleTest {
    private final CheckBigDecimalMisuseRule rule = new CheckBigDecimalMisuseRule();

    @Test
    void findsDoubleConstructorAndEquals() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    boolean run(BigDecimal amount, BigDecimal other, double rate, String text) {
                        BigDecimal a = new BigDecimal(0.1);
                        BigDecimal b = new BigDecimal(rate);
                        BigDecimal c = new BigDecimal(-1.5);
                        boolean same = amount.equals(other);
                        boolean zero = BigDecimal.ZERO.equals(amount);
                        BigDecimal ok1 = new BigDecimal("0.1");
                        BigDecimal ok2 = BigDecimal.valueOf(rate);
                        BigDecimal ok3 = new BigDecimal(10);
                        boolean ok4 = amount.compareTo(other) == 0;
                        return text.equals("x");
                    }
                }
                """)).containsExactly(3, 4, 5, 6, 7);
    }
}
