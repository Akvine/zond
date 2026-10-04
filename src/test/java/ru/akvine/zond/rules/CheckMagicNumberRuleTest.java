package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.codesmell.CheckMagicNumberRule;

import static org.assertj.core.api.Assertions.assertThat;

class CheckMagicNumberRuleTest {
    private final CheckMagicNumberRule rule = new CheckMagicNumberRule();

    @Test
    void findsUnnamedNumbersInExpressions() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private static final int LIMIT = 100;
                    private int size = 16;
                    @Size(max = 255)
                    private String name;
                    int run(int value) {
                        int timeout = 5000;
                        if (value > 42) {
                            return value * 3600;
                        }
                        wait(1500L);
                        double rate = value * 0.15;
                        return value + 1 - 0 * 2;
                    }
                    public int hashCode() {
                        return 31 * size;
                    }
                }
                """)).containsExactly(8, 9, 11, 12);
    }

    @Test
    void ignoresTestClasses() {
        assertThat(RuleTests.lines(rule, """
                class SampleTest {
                    @Test
                    void works() {
                        assertEquals(42, compute(17));
                    }
                }
                """)).isEmpty();
    }
}
