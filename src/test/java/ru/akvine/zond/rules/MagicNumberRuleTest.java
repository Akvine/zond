package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.codesmell.MagicNumberRule;

import static org.assertj.core.api.Assertions.assertThat;

class MagicNumberRuleTest {
    private final MagicNumberRule rule = new MagicNumberRule();

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
                            return value * 37;
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
    void ignoresAnnotationDefaultsRadixAndShiftDistance() {
        assertThat(RuleTests.lines(rule, """
                @interface RateLimiter {
                    int time() default 60;
                }
                class Codec {
                    int decode(String hex, long bits, byte b3, BigInteger big) {
                        int value = Integer.parseInt(hex, 16) + Character.digit(hex.charAt(0), 16);
                        String text = big.toString(36) + new BigInteger(hex, 2);
                        long high = bits >> 16;
                        byte low = (byte) (b3 >>> 6 ^ 0xfc);
                        return Integer.valueOf(16) + value;
                    }
                }
                """)).containsExactly(10);
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
