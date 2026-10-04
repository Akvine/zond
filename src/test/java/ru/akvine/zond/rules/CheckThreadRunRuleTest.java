package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.concurrency.CheckThreadRunRule;

import static org.assertj.core.api.Assertions.assertThat;

class CheckThreadRunRuleTest {
    private final CheckThreadRunRule rule = new CheckThreadRunRule();

    @Test
    void findsRunCalledOnThread() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run(Thread worker, Runnable task) {
                        worker.run();
                        new Thread(task).run();
                        Thread local = new Thread(task);
                        local.run();
                        worker.start();
                        task.run();
                    }
                }
                """)).containsExactly(3, 4, 6);
    }
}
