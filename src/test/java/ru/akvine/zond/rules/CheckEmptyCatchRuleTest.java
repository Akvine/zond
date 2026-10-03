package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckEmptyCatchRuleTest {
    private final CheckEmptyCatchRule rule = new CheckEmptyCatchRule();

    @Test
    void findsOnlyUnexplainedEmptyCatch() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run() {
                        try {
                            work();
                        } catch (IOException e) {
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            // файл необязателен
                        }
                        try {
                            work();
                        } catch (InterruptedException ignored) {
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            log(e);
                        }
                    }
                }
                """)).containsExactly(5);
    }
}
