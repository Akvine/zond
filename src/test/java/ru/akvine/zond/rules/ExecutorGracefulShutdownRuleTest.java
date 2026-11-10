package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.resources.ExecutorGracefulShutdownRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило jr:459: остановка пула потоков, при которой теряются задачи
 */
class ExecutorGracefulShutdownRuleTest {
    private final ExecutorGracefulShutdownRule rule = new ExecutorGracefulShutdownRule();

    @Test
    void gracefulShutdownIsNotReported() {
        assertThat(RuleTests.lines(rule, """
                class Worker {
                    private final ExecutorService executor = Executors.newFixedThreadPool(4);
                    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
                    private final ExecutorService helperPool = Executors.newCachedThreadPool();
                    @PreDestroy
                    void stop() {
                        executor.shutdown();
                        try {
                            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                                executor.shutdownNow();
                                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                                    log.warn("pool did not terminate");
                                }
                            }
                        } catch (InterruptedException e) {
                            executor.shutdownNow();
                            Thread.currentThread().interrupt();
                        }
                        scheduler.shutdown();
                        awaitScheduler();
                        MoreExecutors.shutdownAndAwaitTermination(helperPool, 10, TimeUnit.SECONDS);
                    }
                    private void awaitScheduler() {
                        try {
                            scheduler.awaitTermination(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            scheduler.shutdownNow();
                        }
                    }
                }
                """)).isEmpty();
    }

    @Test
    void shutdownOfBeanPoolMustWaitForTasks() {
        List<Violation> violations = RuleTests.check(rule, """
                class Worker {
                    private final ExecutorService executor = Executors.newFixedThreadPool(4);
                    private final ExecutorService reports = Executors.newSingleThreadExecutor();
                    private final ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
                    private final Scheduler quartz;
                    @PreDestroy
                    void stop() {
                        executor.shutdown();
                        taskExecutor.shutdown();
                        quartz.shutdown();
                    }
                    void close() {
                        reports.shutdownNow();
                    }
                }
                """);

        // Пул Spring ждет задачи по своим настройкам, Quartz - не пул потоков
        assertThat(violations).extracting(Violation::line).containsExactly(8, 13);
        assertThat(violations.get(0).message()).contains("'executor'", "одним shutdown()", "awaitTermination");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        // Прервать задачи сразу могли и намеренно
        assertThat(violations.get(1).message()).contains("'reports'", "сразу прерывают через shutdownNow()");
        assertThat(violations.get(1).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(1).confidence()).isEqualTo(Confidence.SUSPICION);
    }

    @Test
    void waitMustEndWithForcedStop() {
        List<Violation> violations = RuleTests.check(rule, """
                class Worker {
                    private final ExecutorService executor = Executors.newFixedThreadPool(4);
                    private final ExecutorService audit = Executors.newFixedThreadPool(2);
                    private final ExecutorService mailer = Executors.newFixedThreadPool(2);
                    @PreDestroy
                    void stop() throws InterruptedException {
                        executor.shutdown();
                        executor.awaitTermination(30, TimeUnit.SECONDS);
                    }
                    void stopAudit() {
                        audit.shutdown();
                        try {
                            if (!audit.awaitTermination(30, TimeUnit.SECONDS)) {
                                audit.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            log.warn("interrupted", e);
                        }
                    }
                    void stopMailer() throws InterruptedException {
                        mailer.shutdown();
                        if (!mailer.awaitTermination(30, TimeUnit.SECONDS)) {
                            mailer.shutdownNow();
                        }
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(8, 16, 22);
        assertThat(violations.get(0).message()).contains("После awaitTermination()", "'executor'", "shutdownNow()");
        assertThat(violations.get(1).message())
                .contains("прервано, а пул не остановлен", "'audit'", "Thread.currentThread().interrupt()");
        assertThat(violations.get(2).message()).contains("уходит из метода", "'mailer'");
    }

    @Test
    void interruptRestoredElsewhereIsNotRepeated() {
        List<Violation> violations = RuleTests.check(rule, """
                class Worker {
                    private final ExecutorService executor = Executors.newFixedThreadPool(4);
                    void stop() {
                        executor.shutdown();
                        try {
                            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                                executor.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(9);
        assertThat(violations.get(0).message()).contains("в catch вызовите shutdownNow()").doesNotContain("восстановите флаг");
    }

    @Test
    void localPoolIsJudgedByItsOwnMethod() {
        List<Violation> violations = RuleTests.check(rule, """
                class Importer {
                    void fireAndForget(List<Runnable> tasks) {
                        ExecutorService pool = Executors.newFixedThreadPool(4);
                        tasks.forEach(pool::submit);
                        pool.shutdown();
                    }
                    List<String> collect(List<Callable<String>> tasks) throws Exception {
                        ExecutorService pool = Executors.newFixedThreadPool(4);
                        List<Future<String>> futures = pool.invokeAll(tasks);
                        pool.shutdown();
                        return results(futures);
                    }
                    void waitForFutures(List<Callable<String>> tasks) throws Exception {
                        ExecutorService pool = Executors.newFixedThreadPool(4);
                        List<Future<String>> futures = new ArrayList<>();
                        for (Callable<String> task : tasks) {
                            futures.add(pool.submit(task));
                        }
                        for (Future<String> future : futures) {
                            use(future.get());
                        }
                        pool.shutdown();
                    }
                    void untilDone(ExecutorService pool) throws InterruptedException {
                        pool.shutdown();
                        while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                            log.info("waiting");
                        }
                    }
                    void scoped(List<Runnable> tasks) {
                        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                            tasks.forEach(pool::submit);
                        }
                    }
                    void abort(ExecutorService pool) {
                        pool.shutdownNow();
                    }
                    public static void main(String[] args) {
                        ExecutorService pool = Executors.newFixedThreadPool(2);
                        pool.submit(() -> work());
                        pool.shutdown();
                    }
                }
                """);

        // Задачи оставили работать в фоне: возможно, так и задумано - поэтому только подозрение
        assertThat(violations).extracting(Violation::line).containsExactly(5, 26);
        assertThat(violations.get(0).message()).contains("метод не ждет задачи пула 'pool'");
        assertThat(violations.get(0).confidence()).isEqualTo(Confidence.SUSPICION);
        // Ждут до конца, но прерывание уходит из метода, а пул остается работать
        assertThat(violations.get(1).message()).contains("уходит из метода");
    }
}
