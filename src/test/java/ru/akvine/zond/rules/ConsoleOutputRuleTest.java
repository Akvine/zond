package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.codesmell.ConsoleOutputRule;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleOutputRuleTest {
    private final ConsoleOutputRule rule = new ConsoleOutputRule();

    @Test
    void findsPrintStackTraceAndSystemStreams() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run() {
                        try {
                            work();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        System.out.println("done");
                        System.err.printf("%s", "x");
                        log.info("done");
                        e.printStackTrace(writer);
                        out.println("x");
                    }
                }
                """)).containsExactly(6, 8, 9);
    }
}
