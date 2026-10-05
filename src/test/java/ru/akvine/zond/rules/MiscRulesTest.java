package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.TooManyParametersRule;
import ru.akvine.zond.rules.concurrency.TransactionalWithAsyncRule;
import ru.akvine.zond.rules.exceptions.LostExceptionCauseRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:103 - jr:105: транзакции и потоки, сигнатуры методов, исключения
 */
class MiscRulesTest {

    @Test
    void transactionalWithAsync() {
        List<Violation> violations = RuleTests.check(new TransactionalWithAsyncRule(), """
                class Sample {
                    @Async
                    @Transactional
                    public void both() {}
                    @Transactional
                    public void save(Order order) {
                        repository.save(order);
                        executor.submit(() -> notify(order));
                        CompletableFuture.runAsync(() -> audit(order));
                        new Thread(() -> index(order)).start();
                    }
                    public void plain() {
                        executor.submit(() -> work());
                    }
                    @Async
                    public void asyncOnly() {}
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(2, 8, 9, 10);
        assertThat(violations.get(0).message()).contains("вместе с @Async", "'both'");
        assertThat(violations.get(1).message()).contains("submit", "'save'");
    }

    @Test
    void tooManyParameters() {
        assertThat(RuleTests.lines(new TooManyParametersRule(), """
                class Sample {
                    void five(int a, int b, int c, int d, int e) {}
                    void four(int a, int b, int c, int d) {}
                    @Override
                    public void inherited(int a, int b, int c, int d, int e) {}
                    Sample(int a, int b, int c, int d, int e) {}
                }
                """)).containsExactly(2);
    }

    @Test
    void lostExceptionCause() {
        assertThat(RuleTests.lines(new LostExceptionCauseRule(), """
                class Sample {
                    void run() {
                        try {
                            work();
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed");
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed: " + e.getMessage());
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed", e);
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            IllegalStateException wrapped = new IllegalStateException("Failed");
                            wrapped.initCause(e);
                            throw wrapped;
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            throw e;
                        }
                    }
                }
                """)).containsExactly(6, 11);
    }
}
