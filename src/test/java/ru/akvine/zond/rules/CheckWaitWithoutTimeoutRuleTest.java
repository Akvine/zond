package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.concurrency.CheckWaitWithoutTimeoutRule;

import static org.assertj.core.api.Assertions.assertThat;

class CheckWaitWithoutTimeoutRuleTest {
    private final CheckWaitWithoutTimeoutRule rule = new CheckWaitWithoutTimeoutRule();

    @Test
    void findsWaitingWithoutTimeout() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void bad(CountDownLatch latch, Future<String> future, CompletableFuture<String> promise, ExecutorService executor) throws Exception {
                        latch.await();
                        String a = future.get();
                        String b = promise.join();
                        String c = executor.submit(() -> "x").get();
                        CompletableFuture.allOf(promise).join();
                    }
                    void good(CountDownLatch latch, Future<String> future, CompletableFuture<String> promise, Map<String, String> map, Supplier<String> supplier) throws Exception {
                        latch.await(1, TimeUnit.SECONDS);
                        future.get(1, TimeUnit.SECONDS);
                        promise.orTimeout(1, TimeUnit.SECONDS).join();
                        map.get("key");
                        supplier.get();
                        if (future.isDone()) {
                            future.get();
                        }
                    }
                }
                """)).containsExactly(3, 4, 5, 6, 7);
    }
}
