package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.codesmell.PublicStaticNonFinalFieldRule;

import static org.assertj.core.api.Assertions.assertThat;

class PublicStaticNonFinalFieldRuleTest {
    private final PublicStaticNonFinalFieldRule rule = new PublicStaticNonFinalFieldRule();

    @Test
    void findsPublicStaticFieldsWithoutFinal() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    public static int counter;
                    public static String first, second;
                    public static final int LIMIT = 10;
                    private static int hidden;
                    public int instance;
                    static int packagePrivate;
                }
                interface Constants {
                    int MAX = 5;
                }
                """)).containsExactly(2, 3);
    }
}
