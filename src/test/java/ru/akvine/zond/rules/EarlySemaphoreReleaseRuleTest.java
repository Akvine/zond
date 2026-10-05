package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.EarlySemaphoreReleaseRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EarlySemaphoreReleaseRuleTest {
    private final EarlySemaphoreReleaseRule rule = new EarlySemaphoreReleaseRule();

    @Test
    void findsReleaseBeforeWorkIsFinished() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    private final Semaphore semaphore = new Semaphore(1);
                    void early() throws InterruptedException {
                        semaphore.acquire();
                        semaphore.release();
                        work();
                    }
                    void async(ExecutorService executor) throws InterruptedException {
                        semaphore.acquire();
                        try {
                            executor.submit(() -> work());
                        } finally {
                            semaphore.release();
                        }
                    }
                    void correct() throws InterruptedException {
                        semaphore.acquire();
                        try {
                            work();
                        } finally {
                            semaphore.release();
                        }
                    }
                    void asyncCorrect(ExecutorService executor) throws InterruptedException {
                        semaphore.acquire();
                        executor.submit(() -> {
                            try {
                                work();
                            } finally {
                                semaphore.release();
                            }
                        });
                    }
                    void last() throws InterruptedException {
                        semaphore.acquire();
                        work();
                        semaphore.release();
                    }
                    void onlyRelease() {
                        semaphore.release();
                        work();
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(5, 13);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:44"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.CRITICAL);
        assertThat(violations.get(0).message()).contains("до завершения работы");
        assertThat(violations.get(1).message()).contains("асинхронной задачи submit");
    }
}
