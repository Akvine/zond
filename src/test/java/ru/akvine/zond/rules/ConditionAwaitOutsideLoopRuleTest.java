package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.concurrency.ConditionAwaitOutsideLoopRule;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionAwaitOutsideLoopRuleTest {
    private final ConditionAwaitOutsideLoopRule rule = new ConditionAwaitOutsideLoopRule();

    @Test
    void findsConditionAwaitOutsideLoop() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private final Lock lock = new ReentrantLock();
                    private final Condition notEmpty = lock.newCondition();
                    void bad() throws InterruptedException {
                        if (queue.isEmpty()) {
                            notEmpty.await();
                        }
                        notEmpty.awaitNanos(100);
                    }
                    void good(CountDownLatch latch) throws InterruptedException {
                        while (queue.isEmpty()) {
                            notEmpty.await();
                        }
                        latch.await();
                    }
                }
                """)).containsExactly(6, 8);
    }
}
