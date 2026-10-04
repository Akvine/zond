package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.CheckLazyInjectionRule;
import ru.akvine.zond.rules.codesmell.CheckManualRepositoryCreationRule;
import ru.akvine.zond.rules.codesmell.CheckManualServiceCreationRule;
import ru.akvine.zond.rules.concurrency.CheckAsyncVoidRule;
import ru.akvine.zond.rules.concurrency.CheckAsyncWithoutExecutorRule;
import ru.akvine.zond.rules.concurrency.CheckScheduledWithoutLockRule;
import ru.akvine.zond.rules.logical.CheckCacheableSelfInvocationRule;
import ru.akvine.zond.rules.logical.CheckRetryableSelfInvocationRule;
import ru.akvine.zond.rules.logical.CheckTransactionalOnFinalRule;
import ru.akvine.zond.rules.performance.CheckRepositoryCallInLoopRule;
import ru.akvine.zond.rules.resources.CheckTransactionalFileIoRule;
import ru.akvine.zond.rules.resources.CheckTransactionalHttpCallRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:54 - jr:65: работа со Spring
 */
class SpringRulesTest {

    @Test
    void asyncWithoutExecutor() {
        assertThat(RuleTests.lines(new CheckAsyncWithoutExecutorRule(), """
                class Sample {
                    @Async
                    public void first() {}
                    @Async("mailExecutor")
                    public void second() {}
                    @Async(value = "mailExecutor")
                    public void third() {}
                    @Async("")
                    public void fourth() {}
                    public void plain() {}
                }
                @Async
                class Whole {
                }
                """)).containsExactly(2, 8, 12);
    }

    @Test
    void lazyOnInjectedDependency() {
        List<Violation> violations = RuleTests.check(new CheckLazyInjectionRule(), """
                @Service
                class Sample {
                    @Lazy
                    @Autowired
                    private OtherService other;
                    @Lazy
                    private Cache cache;
                    Sample(@Lazy PaymentService payments, OrderService orders) {}
                    @Autowired
                    void setMailer(@Lazy Mailer mailer) {}
                    void process(@Lazy Mailer mailer) {}
                }
                @Lazy
                @Component
                class Heavy {
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 8, 10);
        assertThat(violations.get(0).message()).contains("'other'");
        assertThat(violations.get(1).message()).contains("'payments'");
    }

    @Test
    void cacheableSelfInvocation() {
        List<Violation> violations = RuleTests.check(new CheckCacheableSelfInvocationRule(), """
                class Sample {
                    public User load(Long id) {
                        return findUser(id);
                    }
                    public void refresh(Long id) {
                        this.evict(id);
                        other.findUser(id);
                        helper(id);
                    }
                    @Cacheable("users")
                    public User findUser(Long id) { return null; }
                    @CacheEvict("users")
                    public void evict(Long id) {}
                    private void helper(Long id) {}
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 6);
        assertThat(violations.get(0).message()).contains("@Cacheable", "'findUser'", "'load'");
        assertThat(violations.get(1).message()).contains("@CacheEvict");
    }

    @Test
    void retryableSelfInvocation() {
        List<Violation> violations = RuleTests.check(new CheckRetryableSelfInvocationRule(), """
                class Sample {
                    public void run() {
                        send("a");
                        client.send("a");
                    }
                    @Retryable(maxAttempts = 3)
                    public void send(String message) {}
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3);
        assertThat(violations.get(0).message()).contains("@Retryable", "повторных попыток не будет");
    }

    @Test
    void httpCallInsideTransaction() {
        List<Violation> violations = RuleTests.check(new CheckTransactionalHttpCallRule(), """
                class Sample {
                    @Transactional
                    public void pay(Order order) {
                        repository.save(order);
                        restTemplate.postForObject(url, order, String.class);
                        paymentClient.charge(order);
                        webClient.get().uri(url).retrieve().bodyToMono(String.class).block();
                    }
                    public void notTransactional(Order order) {
                        restTemplate.postForObject(url, order, String.class);
                    }
                    @Transactional
                    public void clean(Order order) {
                        repository.save(order);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(5, 6, 7);
        assertThat(violations.get(0).message()).contains("restTemplate.postForObject", "'pay'");
    }

    @Test
    void fileIoInsideTransaction() {
        assertThat(RuleTests.lines(new CheckTransactionalFileIoRule(), """
                @Transactional
                class Sample {
                    public void export(Path path, MultipartFile upload) throws IOException {
                        Files.writeString(path, "data");
                        InputStream in = new FileInputStream("a.txt");
                        upload.transferTo(path);
                        repository.save(entity);
                    }
                    void notPublic(Path path) throws IOException {
                        Files.writeString(path, "data");
                    }
                }
                """)).containsExactly(4, 5, 6);
    }

    @Test
    void transactionalOnFinal() {
        List<Violation> violations = RuleTests.check(new CheckTransactionalOnFinalRule(), """
                class Sample {
                    @Transactional
                    public final void save() {}
                    @Transactional
                    public static void staticSave() {}
                    @Transactional
                    public void ok() {}
                }
                @Transactional
                final class Closed {
                    public void save() {}
                }
                final class Plain {
                    public void save() {}
                }
                @Transactional
                class Open {
                    public final void locked() {}
                    final void notPublic() {}
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(2, 4, 9, 18);
        assertThat(violations.get(0).message()).contains("final методе 'save'");
        assertThat(violations.get(1).message()).contains("static методе");
        assertThat(violations.get(2).message()).contains("final классе 'Closed'");
    }

    @Test
    void manualServiceCreation() {
        assertThat(RuleTests.lines(new CheckManualServiceCreationRule(), """
                @Service
                class Sample {
                    void run() {
                        OrderService orders = new OrderService(repository);
                        PaymentService payments = new PaymentServiceImpl();
                        Helper helper = new Helper();
                        LocalService local = new LocalService();
                        CompletionService<String> completion = new ExecutorCompletionService<>(executor);
                    }
                }
                class LocalService {
                }
                @Configuration
                class Config {
                    @Bean
                    OrderService orderService() {
                        return new OrderService(null);
                    }
                }
                """)).containsExactly(4, 5);
    }

    @Test
    void manualRepositoryCreation() {
        assertThat(RuleTests.lines(new CheckManualRepositoryCreationRule(), """
                class Sample {
                    void run() {
                        UserRepository users = new UserRepository();
                        OrderDao orders = new OrderDaoImpl(dataSource);
                        List<String> list = new ArrayList<>();
                    }
                }
                class SampleTest {
                    @Test
                    void works() {
                        UserRepository users = new UserRepository();
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void repositoryCallInLoop() {
        assertThat(RuleTests.lines(new CheckRepositoryCallInLoopRule(), """
                class Sample {
                    void run(List<Long> ids) {
                        for (Long id : ids) {
                            userRepository.findById(id);
                        }
                        ids.forEach(id -> orderDao.delete(id));
                        List<User> users = ids.stream().map(id -> userRepository.findById(id)).toList();
                        int i = 0;
                        while (i < 10) {
                            this.userRepository.save(user);
                            i++;
                        }
                        for (User user : userRepository.findAll()) {
                            process(user);
                        }
                        userRepository.findAllById(ids);
                        for (Long id : ids) {
                            service.load(id);
                            NAME_REPOSITORY.matcher(id);
                        }
                        executor.submit(() -> userRepository.findAll());
                    }
                }
                """)).containsExactly(4, 6, 7, 10);
    }

    @Test
    void scheduledWithoutLock() {
        assertThat(RuleTests.lines(new CheckScheduledWithoutLockRule(), """
                class Sample {
                    @Scheduled(fixedRate = 1000)
                    public void sync() {}
                    @Scheduled(cron = "0 0 * * * *")
                    @SchedulerLock(name = "report")
                    public void report() {}
                    @Scheduled(fixedDelay = 1000)
                    public synchronized void guarded() {}
                    @Scheduled(fixedDelay = 1000)
                    public void withFlag() {
                        if (!running.compareAndSet(false, true)) {
                            return;
                        }
                    }
                    public void plain() {}
                }
                """)).containsExactly(2);
    }

    @Test
    void asyncVoid() {
        assertThat(RuleTests.lines(new CheckAsyncVoidRule(), """
                class Sample {
                    @Async
                    public void send() {}
                    @Async
                    public CompletableFuture<String> load() { return null; }
                    public void plain() {}
                }
                @Async
                class Whole {
                    public void first() {}
                    public String second() { return null; }
                    void notPublic() {}
                }
                """)).containsExactly(2, 10);
    }
}
