package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.concurrency.WaitNotifyRule;

import static org.assertj.core.api.Assertions.assertThat;

class WaitNotifyRuleTest {
    private final WaitNotifyRule rule = new WaitNotifyRule();

    @Test
    void findsWaitOutsideLoopAndNotify() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    synchronized void bad() throws InterruptedException {
                        if (!ready) {
                            wait();
                        }
                        notify();
                    }
                    synchronized void good() throws InterruptedException {
                        while (!ready) {
                            lock.wait(1000);
                        }
                        notifyAll();
                    }
                }
                """)).containsExactly(4, 6);
    }
}
