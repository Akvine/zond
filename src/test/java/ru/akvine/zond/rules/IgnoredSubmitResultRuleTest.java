package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.concurrency.IgnoredSubmitResultRule;

import static org.assertj.core.api.Assertions.assertThat;

class IgnoredSubmitResultRuleTest {
    private final IgnoredSubmitResultRule rule = new IgnoredSubmitResultRule();

    @Test
    void findsSubmitWithIgnoredFuture() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private final ExecutorService executor = Executors.newFixedThreadPool(2);
                    void bad(ThreadPoolExecutor pool) {
                        executor.submit(() -> work());
                        pool.submit(this::work);
                        getExecutor().submit(task);
                    }
                    void good(Form form) throws Exception {
                        Future<?> future = executor.submit(() -> work());
                        futures.add(executor.submit(task));
                        executor.execute(() -> work());
                        form.submit(data);
                        executor.submit(task).get(1, TimeUnit.SECONDS);
                        tasks.forEach(task -> executor.submit(task));
                    }
                }
                """)).containsExactly(4, 5, 6);
    }
}
