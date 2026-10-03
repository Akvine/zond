package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:171 - jr:176: потоки
 */
class ThreadRulesTest {

    @Test
    void lockWithoutFinally() {
        assertThat(RuleTests.lines(new CheckLockWithoutFinallyRule(), """
                class Sample {
                    private final Lock lock = new ReentrantLock();
                    void bad() {
                        lock.lock();
                        work();
                        lock.unlock();
                    }
                    void good() {
                        lock.lock();
                        try {
                            work();
                        } finally {
                            lock.unlock();
                        }
                    }
                    void other() {
                        blockService.lock();
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void checkThenAct() {
        assertThat(RuleTests.lines(new CheckCheckThenActRule(), """
                class Sample {
                    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
                    private final Map<String, Session> plain = new HashMap<>();
                    void run(String key, Session session) {
                        if (!sessions.containsKey(key)) {
                            sessions.put(key, session);
                        }
                        if (sessions.get(key) == null) {
                            sessions.put(key, session);
                        }
                        if (!plain.containsKey(key)) {
                            plain.put(key, session);
                        }
                        if (sessions.containsKey(key)) {
                            log(key);
                        }
                        sessions.putIfAbsent(key, session);
                    }
                }
                """)).containsExactly(5, 8);
    }

    @Test
    void nonThreadSafeCollectionInBean() {
        assertThat(RuleTests.lines(new CheckNonThreadSafeCollectionInBeanRule(), """
                @Service
                class CacheService {
                    private final Map<String, String> cache = new HashMap<>();
                    private final Map<String, String> safe = new ConcurrentHashMap<>();
                    private final List<String> readOnly = new ArrayList<>();
                    private final List<String> guarded = new ArrayList<>();
                    void put(String key, String value) {
                        cache.put(key, value);
                        safe.put(key, value);
                    }
                    String first() {
                        return readOnly.get(0);
                    }
                    synchronized void add(String value) {
                        guarded.add(value);
                    }
                }
                class Plain {
                    private final Map<String, String> cache = new HashMap<>();
                    void put(String key, String value) {
                        cache.put(key, value);
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void parallelStreamSideEffect() {
        assertThat(RuleTests.lines(new CheckParallelStreamSideEffectRule(), """
                class Sample {
                    void run(List<String> items) {
                        List<String> results = new ArrayList<>();
                        items.parallelStream().forEach(item -> results.add(item));
                        items.parallelStream().forEach(results::add);
                        List<String> safe = new CopyOnWriteArrayList<>();
                        items.parallelStream().forEach(item -> safe.add(item));
                        items.parallelStream().forEach(safe::add);
                        items.stream().forEach(item -> results.add(item));
                        List<String> collected = items.parallelStream().map(String::trim).toList();
                    }
                }
                """)).containsExactly(4, 5);
    }

    @Test
    void unsynchronizedLazyInit() {
        assertThat(RuleTests.lines(new CheckUnsynchronizedLazyInitRule(), """
                @Service
                class Sample {
                    private Config config;
                    private volatile Config safe;
                    private static Sample instance;
                    Config get() {
                        if (config == null) {
                            config = load();
                        }
                        return config;
                    }
                    static Sample instance() {
                        if (instance == null) {
                            instance = new Sample();
                        }
                        return instance;
                    }
                    synchronized Config locked() {
                        if (config == null) {
                            config = load();
                        }
                        return config;
                    }
                    Config local() {
                        Config value = null;
                        if (value == null) {
                            value = load();
                        }
                        return value;
                    }
                }
                """)).containsExactly(7, 13);
    }

    @Test
    void manualThreadInBean() {
        assertThat(RuleTests.lines(new CheckManualThreadInBeanRule(), """
                @Service
                class Sample {
                    void run() {
                        new Thread(() -> work()).start();
                    }
                }
                class Plain {
                    void run() {
                        new Thread(() -> work()).start();
                    }
                }
                """)).containsExactly(4);
    }
}
