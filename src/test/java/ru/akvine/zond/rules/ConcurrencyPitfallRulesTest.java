package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.AtomicReferenceIdentityRule;
import ru.akvine.zond.rules.concurrency.ComputeIfAbsentMisuseRule;
import ru.akvine.zond.rules.concurrency.SecurityContextInAsyncRule;
import ru.akvine.zond.rules.concurrency.VirtualThreadPitfallRule;
import ru.akvine.zond.rules.datetime.StaticSimpleDateFormatRule;
import ru.akvine.zond.rules.resources.ExecutorQueueSettingsRule;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:480, jr:490 - jr:493 и дополнение к jr:9: контекст в другом потоке, блокировки карты, пулы
 */
class ConcurrencyPitfallRulesTest {
    private static final String WORKER = """
            class Worker {
                private final RestTemplate restTemplate;
                private final OrderRepository orderRepository;
                private final Object lock = new Object();
                synchronized String price(String id) {
                    return restTemplate.getForObject("http://prices/" + id, String.class);
                }
                void save(Order order) {
                    synchronized (lock) {
                        orderRepository.save(order);
                    }
                }
                synchronized int size() {
                    return items.size();
                }
                ExecutorService pool() {
                    return Executors.newFixedThreadPool(100, Thread.ofVirtual().factory());
                }
                ExecutorService good() {
                    return Executors.newVirtualThreadPerTaskExecutor();
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void securityContextIsEmptyInAnotherThread() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Users.java", """
                class Users {
                    String current() {
                        return SecurityContextHolder.getContext().getAuthentication().getName();
                    }
                }
                """);
        files.put("Notifier.java", """
                @Service
                class Notifier {
                    private final Users users;
                    private final ExecutorService executor;
                    @Async
                    public void notifyUser() {
                        String name = SecurityContextHolder.getContext().getAuthentication().getName();
                    }
                    @Async
                    public void audit() {
                        log.info("by {}", users.current());
                    }
                    public void later(List<Order> orders) {
                        executor.submit(() -> users.current());
                        orders.parallelStream().forEach(order -> order.setAuthor(users.current()));
                        CompletableFuture.runAsync(() -> RequestContextHolder.currentRequestAttributes());
                    }
                    @Scheduled(fixedDelay = 1000)
                    public void cleanup() {
                        String name = SecurityContextHolder.getContext().getAuthentication().getName();
                    }
                    @Scheduled(cron = "0 0 * * * *")
                    public void asSystem() {
                        SecurityContextHolder.getContext().setAuthentication(systemUser);
                        String name = SecurityContextHolder.getContext().getAuthentication().getName();
                    }
                    public String now() {
                        String name = users.current();
                        orders.stream().forEach(order -> order.setAuthor(users.current()));
                        return SecurityContextHolder.getContext().getAuthentication().getName();
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new SecurityContextInAsyncRule(), files);

        // В потоке запроса и в обычном стриме контекст на месте; задача, которая задает контекст сама, - тоже не находка
        assertThat(violations).extracting(Violation::line).containsExactly(7, 11, 14, 15, 16, 20);
        assertThat(violations.get(0).message())
                .contains("SecurityContextHolder читается в @Async-методе 'notifyUser'", "ThreadLocal", "вернет null");
        assertThat(violations.get(1).message()).contains("через вызов 'current(...)'");
        assertThat(violations.get(2).message()).contains("в задаче, переданной в 'submit(...)'");
        assertThat(violations.get(3).message()).contains("параллельном стриме");
        assertThat(violations.get(4).message()).contains("RequestContextHolder", "IllegalStateException");
        assertThat(violations.get(5).message()).contains("задаче по расписанию 'cleanup'", "нет вовсе");

        // Передача контекста настроена - находок нет
        files.put("AsyncConfig.java", """
                @Configuration
                class AsyncConfig {
                    @Bean
                    Executor taskExecutor(ThreadPoolTaskExecutor delegate) {
                        return new DelegatingSecurityContextAsyncTaskExecutor(delegate);
                    }
                }
                """);
        assertThat(RuleTests.checkProject(new SecurityContextInAsyncRule(), files)).isEmpty();
    }

    @Test
    void functionOfComputeIfAbsentRunsUnderLock() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Prices.java", """
                class Prices {
                    private final Map<Integer, Long> memo = new ConcurrentHashMap<>();
                    private final Map<String, Price> cache = new ConcurrentHashMap<>();
                    private final Map<String, Price> local = new HashMap<>();
                    private final RestTemplate restTemplate;
                    private final PriceRepository priceRepository;
                    long fib(int n) {
                        return n < 2 ? n : memo.computeIfAbsent(n, key -> fib(key - 1) + fib(key - 2));
                    }
                    Price alias(String code) {
                        return cache.computeIfAbsent(code, key -> cache.put(key + ":alias", new Price(key)));
                    }
                    Price remote(String code) {
                        return cache.computeIfAbsent(code, key -> restTemplate.getForObject("/prices/" + key, Price.class));
                    }
                    Price stored(String code) {
                        return cache.computeIfAbsent(code, priceRepository::findByCode);
                    }
                    Price hidden(String code) {
                        return cache.computeIfAbsent(code, key -> load(key));
                    }
                    Price simple(String code) {
                        return cache.computeIfAbsent(code, Price::new);
                    }
                    Price single(String code) {
                        return local.computeIfAbsent(code, key -> restTemplate.getForObject("/prices/" + key, Price.class));
                    }
                    private Price load(String code) {
                        return priceRepository.findByCode(code);
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new ComputeIfAbsentMisuseRule(), files);

        // Обычная HashMap блокировки не держит, а создание объекта - короткая работа
        assertThat(violations).extracting(Violation::line).containsExactly(8, 11, 14, 17, 20);
        assertThat(violations.get(0).message()).contains("рекурсивно вызывает 'fib(...)'", "Recursive update");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.CRITICAL);
        assertThat(violations.get(1).message()).contains("меняет ту же карту ('cache.put(");
        assertThat(violations.get(2).message()).contains("'restTemplate.getForObject' под блокировкой карты", "Caffeine");
        assertThat(violations.get(2).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(3).message()).contains("'priceRepository::findByCode'");
        assertThat(violations.get(4).message()).contains("'priceRepository.findByCode'");
    }

    @Test
    void atomicReferenceComparesReferences() {
        List<Violation> violations = RuleTests.check(new AtomicReferenceIdentityRule(), """
                class Counter {
                    private final AtomicReference<Integer> version = new AtomicReference<>(0);
                    private final AtomicReference<String> state = new AtomicReference<>("NEW");
                    private final AtomicReference<Boolean> started = new AtomicReference<>(false);
                    private final AtomicReference<Config> config = new AtomicReference<>();
                    private final AtomicInteger hits = new AtomicInteger();
                    boolean bump(Integer expected) {
                        return version.compareAndSet(expected, expected + 1);
                    }
                    boolean start() {
                        return state.compareAndSet("NEW", "RUNNING");
                    }
                    void next() {
                        Integer current = version.get();
                        version.compareAndSet(current, current + 1);
                        Integer seen;
                        do {
                            seen = version.get();
                        } while (!version.compareAndSet(seen, seen + 1));
                    }
                    void other(Config old, Config fresh) {
                        started.compareAndSet(false, true);
                        config.compareAndSet(old, fresh);
                        hits.compareAndSet(1, 2);
                    }
                }
                """);

        // Значение, прочитанное из той же ссылки, Boolean, свой объект и AtomicInteger сравниваются верно
        assertThat(violations).extracting(Violation::line).containsExactly(8, 11);
        assertThat(violations.get(0).message())
                .contains("у AtomicReference<Integer>", "по ссылке (==)", "AtomicInteger / AtomicLong", "ref.get()");
        assertThat(violations.get(1).message()).contains("у AtomicReference<String>");
    }

    @Test
    void virtualThreadsPinOnSynchronizedAndNeedNoPool() {
        VirtualThreadPitfallRule rule = new VirtualThreadPitfallRule();
        List<Violation> violations = new ProjectFixture(dir).source("Worker.java", WORKER).check(rule);

        assertThat(violations).extracting(Violation::line).containsExactly(6, 10, 17);
        assertThat(violations.get(0).message())
                .contains("'restTemplate.getForObject' внутри synchronized", "поток-носитель", "ReentrantLock");
        assertThat(violations.get(1).message()).contains("'orderRepository.save'");
        assertThat(violations.get(2).message()).contains("Пул из виртуальных потоков", "Semaphore");
        assertThat(violations.get(2).errorLevel()).isEqualTo(ErrorLevel.MINOR);

        // Без виртуальных потоков блокировка в synchronized - забота другого правила
        String platform = WORKER.replace("Thread.ofVirtual().factory()", "factory")
                .replace("Executors.newVirtualThreadPerTaskExecutor()", "Executors.newCachedThreadPool(factory)");
        assertThat(new ProjectFixture(dir.resolve("platform")).source("Worker.java", platform).check(rule)).isEmpty();

        // Виртуальные потоки включены настройкой Spring Boot
        List<Violation> byConfig = new ProjectFixture(dir.resolve("boot")).source("Worker.java", platform)
                .write("src/main/resources/application.properties", "spring.threads.virtual.enabled=true\n")
                .check(rule);
        assertThat(byConfig).extracting(Violation::line).containsExactly(6, 10);
    }

    @Test
    void maxPoolSizeNeedsBoundedQueue() {
        ExecutorQueueSettingsRule rule = new ExecutorQueueSettingsRule();
        List<Violation> violations = new ProjectFixture(dir).source("Pools.java", """
                        @Configuration
                        class Pools {
                            @Bean
                            ThreadPoolTaskExecutor growing() {
                                ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                                executor.setCorePoolSize(4);
                                executor.setMaxPoolSize(32);
                                return executor;
                            }
                            @Bean
                            ThreadPoolTaskExecutor fixed() {
                                ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                                executor.setCorePoolSize(8);
                                executor.setMaxPoolSize(8);
                                return executor;
                            }
                            @Bean
                            ThreadPoolTaskExecutor bounded() {
                                ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                                executor.setCorePoolSize(4);
                                executor.setMaxPoolSize(32);
                                executor.setQueueCapacity(100);
                                return executor;
                            }
                            @Bean
                            ThreadPoolTaskExecutor complete() {
                                ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                                executor.setMaxPoolSize(32);
                                executor.setQueueCapacity(100);
                                executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
                                return executor;
                            }
                            ExecutorService raw() {
                                return new ThreadPoolExecutor(4, 16, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));
                            }
                            ExecutorService rawWithPolicy() {
                                return new ThreadPoolExecutor(4, 16, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
                                        new ThreadPoolExecutor.CallerRunsPolicy());
                            }
                        }
                        """)
                .write("src/main/resources/application.properties",
                        "spring.task.execution.pool.core-size=8\nspring.task.execution.pool.max-size=64\n")
                .check(rule);

        // Пул постоянного размера расти и не должен; с политикой отказа и размером очереди все задано
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("Pools.java:7", "Pools.java:22", "Pools.java:34", "application.properties:2");
        assertThat(violations.get(0).message())
                .contains("'executor.setMaxPoolSize(32)' без setQueueCapacity(...)", "только когда очередь заполнена");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(violations.get(1).message()).contains("без setRejectedExecutionHandler(...)", "CallerRunsPolicy");
        assertThat(violations.get(1).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(2).message()).contains("new ThreadPoolExecutor(...) с ограниченной очередью");
        assertThat(violations.get(3).message()).contains("spring.task.execution.pool.queue-capacity");
    }

    @Test
    void sharedFormattersAndDigestsAreNotThreadSafe() {
        List<Violation> violations = RuleTests.check(new StaticSimpleDateFormatRule(), """
                class Formats {
                    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd");
                    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
                    private static final NumberFormat PERCENT = NumberFormat.getPercentInstance();
                    private static MessageDigest digest;
                    private static final ThreadLocal<DecimalFormat> SAFE = ThreadLocal.withInitial(DecimalFormat::new);
                    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_DATE;
                    private static final Pattern CODE = Pattern.compile("[A-Z]+");
                    private final DecimalFormat own = new DecimalFormat("0.0");
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(2, 3, 4, 5);
        assertThat(violations.get(0).message()).contains("SimpleDateFormat в статическом поле 'DATE'", "java.time");
        assertThat(violations.get(1).message()).contains("DecimalFormat в статическом поле 'MONEY'", "ThreadLocal");
        assertThat(violations.get(3).message()).contains("MessageDigest", "внутреннее состояние");
    }
}
