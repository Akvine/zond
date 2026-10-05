package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.exceptions.EmptyCatchRule;

import static org.assertj.core.api.Assertions.assertThat;

class EmptyCatchRuleTest {
    private final EmptyCatchRule rule = new EmptyCatchRule();

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
