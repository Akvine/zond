package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckSystemExitRuleTest {
    private final CheckSystemExitRule rule = new CheckSystemExitRule();

    @Test
    void findsExitOutsideMain() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void stop() {
                        System.exit(1);
                        Runtime.getRuntime().halt(2);
                    }
                    public static void main(String[] args) {
                        System.exit(0);
                    }
                    void ok() {
                        service.exit();
                    }
                }
                """)).containsExactly(3, 4);
    }
}
