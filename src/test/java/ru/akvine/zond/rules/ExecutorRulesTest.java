package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.codesmell.CheckExecutorWithoutMdcRule;
import ru.akvine.zond.rules.concurrency.CheckFutureGetInLoopRule;
import ru.akvine.zond.rules.resources.CheckExecutorNotShutdownRule;
import ru.akvine.zond.rules.resources.CheckThreadLocalNotRemovedRule;
import ru.akvine.zond.rules.resources.CheckUnboundedExecutorRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:76 - jr:79 и jr:88: ThreadLocal, пулы потоков, Future
 */
class ExecutorRulesTest {

    @Test
    void threadLocalNotRemoved() {
        assertThat(RuleTests.lines(new CheckThreadLocalNotRemovedRule(), """
                class Sample {
                    private static final ThreadLocal<String> LEAKED = new ThreadLocal<>();
                    private static final ThreadLocal<String> CLEANED = new ThreadLocal<>();
                    private static final ThreadLocal<String> READ_ONLY = ThreadLocal.withInitial(() -> "x");
                    void run() {
                        LEAKED.set("a");
                        CLEANED.set("a");
                        try {
                            work(READ_ONLY.get());
                        } finally {
                            CLEANED.remove();
                        }
                    }
                }
                """)).containsExactly(2);
    }

    @Test
    void unboundedExecutor() {
        assertThat(RuleTests.lines(new CheckUnboundedExecutorRule(), """
                class Sample {
                    ExecutorService a = Executors.newCachedThreadPool();
                    ExecutorService b = Executors.newFixedThreadPool(4);
                    ExecutorService c = new ThreadPoolExecutor(2, 4, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
                    ExecutorService d = new ThreadPoolExecutor(2, Integer.MAX_VALUE, 1, TimeUnit.MINUTES, new SynchronousQueue<>());
                    ExecutorService ok1 = new ThreadPoolExecutor(2, 4, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(100));
                    ExecutorService ok2 = new ThreadPoolExecutor(2, 4, 1, TimeUnit.MINUTES, new ArrayBlockingQueue<>(100));
                }
                """)).containsExactly(2, 3, 4, 5);
    }

    @Test
    void executorNotShutdown() {
        assertThat(RuleTests.lines(new CheckExecutorNotShutdownRule(), """
                class Sample {
                    private final ExecutorService leaked = Executors.newFixedThreadPool(2);
                    private final ExecutorService managed = Executors.newFixedThreadPool(2);
                    void run() {
                        ExecutorService local = Executors.newSingleThreadExecutor();
                        local.submit(() -> work());
                        ExecutorService closed = Executors.newSingleThreadExecutor();
                        closed.submit(() -> work());
                        closed.shutdown();
                        try (ExecutorService scoped = Executors.newSingleThreadExecutor()) {
                            scoped.submit(() -> work());
                        }
                    }
                    ExecutorService create() {
                        ExecutorService created = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
                        return created;
                    }
                    @PreDestroy
                    void stop() {
                        managed.shutdownNow();
                    }
                }
                """)).containsExactly(2, 5);
    }

    @Test
    void futureGetInLoop() {
        assertThat(RuleTests.lines(new CheckFutureGetInLoopRule(), """
                class Sample {
                    void run(ExecutorService executor, List<Callable<String>> tasks, List<Future<String>> futures) throws Exception {
                        for (Callable<String> task : tasks) {
                            Future<String> future = executor.submit(task);
                            use(future.get());
                            use(executor.submit(task).get());
                        }
                        for (Future<String> future : futures) {
                            use(future.get());
                        }
                        tasks.forEach(task -> CompletableFuture.supplyAsync(() -> "x").join());
                    }
                }
                """)).containsExactly(5, 6, 11);
    }

    @Test
    void executorWithoutMdc() {
        assertThat(RuleTests.lines(new CheckExecutorWithoutMdcRule(), """
                class Sample {
                    ExecutorService a = Executors.newFixedThreadPool(2);
                    ExecutorService b = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
                }
                """)).containsExactly(2, 3);

        assertThat(RuleTests.lines(new CheckExecutorWithoutMdcRule(), """
                class Sample {
                    ExecutorService a = Executors.newFixedThreadPool(2);
                    void submit(Runnable task) {
                        Map<String, String> context = MDC.getCopyOfContextMap();
                        a.submit(() -> {
                            MDC.setContextMap(context);
                            task.run();
                        });
                    }
                }
                """)).isEmpty();
    }
}
