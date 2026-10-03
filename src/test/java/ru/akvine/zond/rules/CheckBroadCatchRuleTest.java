package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckBroadCatchRuleTest {
    private final CheckBroadCatchRule rule = new CheckBroadCatchRule();

    @Test
    void findsBroadCatchWithoutRethrow() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run() {
                        try {
                            work();
                        } catch (Exception e) {
                            log(e);
                        }
                        try {
                            work();
                        } catch (IOException | Throwable e) {
                            log(e);
                        }
                        try {
                            work();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            log(e);
                        }
                        try {
                            work();
                        } catch (Exception e) {
                        }
                    }
                }
                """)).containsExactly(5, 10);
    }
}
