package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.WaitNotifyOutsideSynchronizedRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило jr:471: wait(), notify() и notifyAll() без монитора объекта
 */
class WaitNotifyOutsideSynchronizedRuleTest {
    private final WaitNotifyOutsideSynchronizedRule rule = new WaitNotifyOutsideSynchronizedRule();

    @Test
    void monitorMustBeHeld() {
        List<Violation> violations = RuleTests.check(rule, """
                class Mailbox {
                    private final Object lock = new Object();
                    private final Object other = new Object();
                    void await() throws InterruptedException {
                        lock.wait();
                    }
                    void signal() {
                        notifyAll();
                    }
                    void wrongMonitor() {
                        synchronized (this) {
                            lock.notify();
                        }
                    }
                    static synchronized void wrongStatic(Object queue) {
                        queue.notifyAll();
                    }
                    void timed() throws InterruptedException {
                        this.lock.wait(1000);
                    }
                    Runnable task() {
                        return new Runnable() {
                            @Override
                            public void run() {
                                lock.notifyAll();
                            }
                        };
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(5, 8, 12, 16, 19, 25);
        assertThat(violations.get(0).message())
                .contains("'lock.wait()' вне synchronized", "IllegalMonitorStateException", "synchronized (lock)");
        assertThat(violations.get(1).message()).contains("'notifyAll()' вне synchronized", "объекта 'this'");
        assertThat(violations.get(2).message()).contains("по другому объекту ('this')", "монитор 'lock'");
    }

    @Test
    void heldMonitorIsNotReported() {
        assertThat(RuleTests.lines(rule, """
                class Mailbox {
                    private final Object lock = new Object();
                    synchronized void await() throws InterruptedException {
                        while (empty()) {
                            wait();
                        }
                    }
                    synchronized void put(Object message) {
                        this.notifyAll();
                    }
                    void awaitOnLock() throws InterruptedException {
                        synchronized (lock) {
                            while (empty()) {
                                lock.wait(500);
                            }
                            this.lock.notifyAll();
                        }
                    }
                    static void awaitClass() throws InterruptedException {
                        synchronized (Mailbox.class) {
                            Mailbox.class.wait();
                        }
                    }
                    synchronized void take() {
                        waitQuietly();
                    }
                    private void waitQuietly() {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    void later(Executor executor) {
                        executor.execute(() -> lock.notifyAll());
                    }
                    void notRelated(Condition ready, Listener listener) throws InterruptedException {
                        ready.await();
                        ready.signalAll();
                        listener.notify("done");
                    }
                }
                """)).isEmpty();
    }
}
