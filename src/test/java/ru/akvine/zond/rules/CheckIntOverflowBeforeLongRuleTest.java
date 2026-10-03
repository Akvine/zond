package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckIntOverflowBeforeLongRuleTest {
    private final CheckIntOverflowBeforeLongRule rule = new CheckIntOverflowBeforeLongRule();

    @Test
    void findsIntMultiplicationStoredAsLong() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private long total;
                    long run(int count, int price, long big, List<String> items) {
                        long r1 = count * price;
                        long r2 = 1000 * 60 * 60 * 24 * 30;
                        long r3 = items.size() * 1024;
                        this.total = count * price + 1;
                        long ok1 = 24 * 60 * 60 * 1000;
                        long ok2 = (long) count * price;
                        long ok3 = big * count;
                        long ok4 = 1000L * count * price;
                        long ok5 = count + price;
                        int ok6 = count * price;
                        return count * 1000;
                    }
                }
                """)).containsExactly(4, 5, 6, 7, 14);
    }
}
