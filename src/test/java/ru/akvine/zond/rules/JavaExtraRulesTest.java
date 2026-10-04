package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.codesmell.CheckOptionalAsFieldOrParameterRule;
import ru.akvine.zond.rules.concurrency.CheckSwallowedInterruptRule;
import ru.akvine.zond.rules.concurrency.CheckSynchronizedOnBadLockRule;
import ru.akvine.zond.rules.concurrency.CheckVolatileNonAtomicRule;
import ru.akvine.zond.rules.datetime.CheckDatePatternRule;
import ru.akvine.zond.rules.exceptions.CheckUnhandledNumberFormatRule;
import ru.akvine.zond.rules.logical.CheckArrayMethodsRule;
import ru.akvine.zond.rules.logical.CheckArraysAsListPrimitiveRule;
import ru.akvine.zond.rules.logical.CheckOptionalOfNullableRule;
import ru.akvine.zond.rules.performance.CheckOptionalOrElseCallRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:114, jr:126 - jr:134: исключения, потоки, даты, Optional, массивы
 */
class JavaExtraRulesTest {

    @Test
    void unhandledNumberFormat() {
        assertThat(RuleTests.lines(new CheckUnhandledNumberFormatRule(), """
                class Sample {
                    int run(String value) {
                        int a = Integer.parseInt(value);
                        try {
                            return Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            return 0;
                        }
                    }
                    long other(String value) {
                        try {
                            work();
                        } catch (IOException e) {
                            return Long.parseLong(value);
                        }
                        return Integer.parseInt("42") + parser.parseInt(value);
                    }
                }
                """)).containsExactly(3, 14);
    }

    @Test
    void swallowedInterrupt() {
        assertThat(RuleTests.lines(new CheckSwallowedInterruptRule(), """
                class Sample {
                    void run() {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            log.warn("interrupted");
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        try {
                            future.get();
                        } catch (InterruptedException | ExecutionException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                """)).containsExactly(5);
    }

    @Test
    void synchronizedOnBadLock() {
        assertThat(RuleTests.lines(new CheckSynchronizedOnBadLockRule(), """
                class Sample {
                    private Object mutableLock = new Object();
                    private final Object lock = new Object();
                    private final String name = "x";
                    void run(Integer id) {
                        synchronized ("lock") { work(); }
                        synchronized (mutableLock) { work(); }
                        synchronized (name) { work(); }
                        synchronized (id) { work(); }
                        synchronized (lock) { work(); }
                        synchronized (this) { work(); }
                    }
                }
                """)).containsExactly(6, 7, 8, 9);
    }

    @Test
    void volatileNonAtomic() {
        assertThat(RuleTests.lines(new CheckVolatileNonAtomicRule(), """
                class Sample {
                    private volatile int counter;
                    private volatile boolean stopped;
                    private int plain;
                    void run() {
                        counter++;
                        counter += 2;
                        counter = counter + 1;
                        stopped = true;
                        plain++;
                        counter = 0;
                    }
                    synchronized void safe() {
                        counter++;
                    }
                }
                """)).containsExactly(6, 7, 8);
    }

    @Test
    void datePattern() {
        assertThat(RuleTests.lines(new CheckDatePatternRule(), """
                class Sample {
                    DateTimeFormatter a = DateTimeFormatter.ofPattern("YYYY-MM-dd");
                    DateTimeFormatter b = DateTimeFormatter.ofPattern("yyyy-MM-DD");
                    SimpleDateFormat c = new SimpleDateFormat("dd.MM.yyyy hh:mm");
                    @JsonFormat(pattern = "YYYY-MM-dd")
                    private LocalDate date;
                    DateTimeFormatter ok1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                    DateTimeFormatter ok2 = DateTimeFormatter.ofPattern("hh:mm a");
                    DateTimeFormatter ok3 = DateTimeFormatter.ofPattern("YYYY-'W'ww");
                    String text = "YYYY";
                }
                """)).containsExactly(2, 3, 4, 5);
    }

    @Test
    void optionalAsFieldOrParameter() {
        assertThat(RuleTests.lines(new CheckOptionalAsFieldOrParameterRule(), """
                class Sample {
                    private Optional<String> name;
                    private String title;
                    void run(Optional<String> value, String other) {}
                    Sample(Optional<Long> id) {}
                    Optional<String> find() {
                        return items.stream().map(item -> item).findFirst();
                    }
                }
                """)).containsExactly(2, 4, 5);
    }

    @Test
    void optionalOrElseCall() {
        assertThat(RuleTests.lines(new CheckOptionalOrElseCallRule(), """
                class Sample {
                    void run(Optional<User> user) {
                        User a = user.orElse(loadDefault());
                        User b = user.orElse(new User());
                        User c = user.orElse(repository.findDefault());
                        User ok1 = user.orElse(DEFAULT);
                        User ok2 = user.orElseGet(() -> loadDefault());
                        List<String> ok3 = names.orElse(Collections.emptyList());
                        User ok4 = user.orElse(null);
                        User ok5 = user.orElse(holder.getDefaultUser());
                    }
                }
                """)).containsExactly(3, 4, 5);
    }

    @Test
    void optionalOfNullable() {
        assertThat(RuleTests.lines(new CheckOptionalOfNullableRule(), """
                class Sample {
                    void run(Map<String, User> map, String key) {
                        Optional<User> a = Optional.of(map.get(key));
                        Optional<User> b = Optional.of(repository.findByName(key));
                        Optional<String> c = Optional.of(System.getenv("HOME"));
                        Optional<User> ok1 = Optional.ofNullable(map.get(key));
                        Optional<User> ok2 = Optional.of(new User());
                        Optional<String> ok3 = Optional.of(key.trim());
                        Optional<String> ok4 = Optional.of(names.get(0));
                    }
                }
                """)).containsExactly(3, 4, 5);
    }

    @Test
    void arrayMethods() {
        assertThat(RuleTests.lines(new CheckArrayMethodsRule(), """
                class Sample {
                    private byte[] data;
                    boolean run(int[] numbers, int[] other, List<String> list) {
                        String text = numbers.toString();
                        int hash = this.data.hashCode();
                        boolean ok1 = Arrays.equals(numbers, other);
                        String ok2 = list.toString();
                        return numbers.equals(other);
                    }
                }
                """)).containsExactly(4, 5, 8);
    }

    @Test
    void arraysAsListPrimitive() {
        assertThat(RuleTests.lines(new CheckArraysAsListPrimitiveRule(), """
                class Sample {
                    void run(int[] numbers, String[] names, Integer[] boxed) {
                        List<int[]> a = Arrays.asList(numbers);
                        List<String> ok1 = Arrays.asList(names);
                        List<Integer> ok2 = Arrays.asList(boxed);
                        List<Integer> ok3 = Arrays.asList(1, 2, 3);
                    }
                }
                """)).containsExactly(3);
    }
}
