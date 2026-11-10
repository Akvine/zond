package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.AsyncSelfInvocationRule;
import ru.akvine.zond.rules.logical.FinalMethodInProxiedBeanRule;
import ru.akvine.zond.rules.logical.ProxyCallInInitMethodRule;
import ru.akvine.zond.rules.logical.TransactionalOnFinalRule;
import ru.akvine.zond.rules.logical.TransactionalSelfInvocationRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:460 и jr:461: расчет на прокси при инициализации бина и final в бине с прокси
 */
class ProxyLifecycleRulesTest {
    private static final String CATALOG_CACHE = """
            @Service
            class CatalogCache {
                @Autowired @Lazy
                private CatalogCache self;
                private ObjectProvider<CatalogCache> selfProvider;
                private ApplicationContext context;
                private PriceService priceService;

                @PostConstruct
                @Transactional
                public void init() {
                    self.loadAll();
                    loadAll();
                    priceService.refresh();
                    warmUp();
                }
                private void warmUp() {
                    context.getBean(CatalogCache.class).refresh();
                    selfProvider.getObject().loadAll();
                    ((CatalogCache) AopContext.currentProxy()).loadAll();
                    self.plain();
                    refresh();
                }
                @Transactional
                public void loadAll() {}
                @Async
                public void refresh() {}
                public void plain() {}
                public void later() {
                    self.loadAll();
                }
            }
            """;

    @Test
    void initMethodCannotRelyOnProxy() {
        List<Violation> violations = RuleTests.check(new ProxyCallInInitMethodRule(), CATALOG_CACHE);

        // Не считаются: вызов другого бина, своего метода без аннотаций и вызов через self вне инициализации
        assertThat(violations).extracting(Violation::line).containsExactly(10, 12, 18, 19, 20);
        assertThat(violations.get(0).message())
                .contains("@Transactional на методе инициализации 'init'", "транзакция не откроется", "TransactionTemplate");
        assertThat(violations.get(1).message())
                .contains("@Transactional-метода 'loadAll'", "('self')", "из метода инициализации 'init'",
                        "BeanCurrentlyInCreationException", "ApplicationReadyEvent");
        assertThat(violations.get(2).message())
                .contains("@Async-метода 'refresh'", "context.getBean(CatalogCache.class)",
                        "из метода 'warmUp'", "('init' -> 'warmUp')", "в потоке запуска приложения");
    }

    @Test
    void directCallFromInitMethodIsLeftToSelfInvocationRules() {
        String code = """
                @Service
                class Warmer {
                    @PostConstruct
                    public void init() {
                        loadAll();
                        refresh();
                    }
                    @Transactional
                    public void loadAll() {}
                    @Async
                    public void refresh() {}
                }
                """;

        // Вызов через this идет мимо прокси из любого метода, не только при инициализации: находка у jr:2 и jr:157
        assertThat(RuleTests.lines(new TransactionalSelfInvocationRule(), code)).containsExactly(5);
        assertThat(RuleTests.lines(new AsyncSelfInvocationRule(), code)).containsExactly(6);
        assertThat(RuleTests.lines(new ProxyCallInInitMethodRule(), code)).isEmpty();
    }

    @Test
    void initMethodIsRecognizedByInterfaceAndClassAnnotation() {
        List<Violation> violations = RuleTests.check(new ProxyCallInInitMethodRule(), """
                @Service
                @Transactional
                class Importer implements InitializingBean {
                    private ImportRepository importRepository;
                    @Override
                    public void afterPropertiesSet() {
                        importRepository.deleteAll();
                        importRepository.saveAll(defaults());
                    }
                }
                @Service
                @Transactional
                class Registry implements InitializingBean {
                    private final Map<String, Handler> handlers = new HashMap<>();
                    @Override
                    public void afterPropertiesSet() {
                        handlers.put("default", new DefaultHandler());
                    }
                }
                @Service
                class Mailer {
                    @Async
                    @PostConstruct
                    void start() {}
                    @PostConstruct
                    @Retryable(maxAttempts = 5)
                    public void connect() {}
                }
                @Service
                class Notifier implements NotificationPort {
                    private NotificationPort self;
                    private NotificationPort fallback;
                    @PostConstruct
                    void init() {
                        self.send("started");
                        fallback.send("started");
                    }
                    @Retryable
                    public void send(String text) {}
                }
                @Service
                class Plain {
                    private Repository repository;
                    @PostConstruct
                    void init() {
                        repository.findAll();
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(5, 22, 26, 35);
        assertThat(violations.get(0).message())
                .contains("@Transactional на классе 'Importer'", "'afterPropertiesSet'", "без общей транзакции");
        assertThat(violations.get(1).message()).contains("@Async на методе инициализации 'start'", "синхронно");
        assertThat(violations.get(2).message()).contains("@Retryable", "повторных попыток не будет");
        assertThat(violations.get(3).message()).contains("@Retryable-метода 'send'", "('self')");
    }

    @Test
    void finalMethodOfProxiedBeanRunsOnEmptyProxy() {
        String code = """
                @Service
                class OrderService {
                    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
                    private final OrderRepository repository;
                    private final int limit = 100;
                    @Transactional
                    public void save(Order order) {
                        repository.save(order);
                    }
                    public final Order find(Long id) {
                        return repository.findById(id).orElseThrow();
                    }
                    public final Order findChecked(Long id) {
                        return load(id);
                    }
                    public final int limit() {
                        return limit;
                    }
                    public final void describe() {
                        log.info("orders");
                    }
                    public final void delegate(Order order) {
                        save(order);
                    }
                    public final String shadowed(String repository) {
                        return repository.trim();
                    }
                    final Order internal(Long id) {
                        return repository.getReferenceById(id);
                    }
                    @Async
                    public final void announce(Order order) {
                        sender.send(order);
                    }
                    private Order load(Long id) {
                        return repository.findById(id).orElseThrow();
                    }
                }
                class Plain {
                    private final OrderRepository repository;
                    public final Order find(Long id) {
                        return repository.findById(id).orElseThrow();
                    }
                }
                @Service
                final class Mailer {
                    @Async
                    public void send() {}
                }
                @Validated
                @Component
                class Settings {
                    private String url;
                    public final String url() {
                        return this.url;
                    }
                }
                @Service
                class Ledger {
                    private LedgerRepository repository;
                    @Transactional
                    public final void post() {
                        repository.flush();
                    }
                }
                @Transactional
                final class Closed {
                    public void save() {}
                }
                """;
        List<Violation> violations = RuleTests.check(new FinalMethodInProxiedBeanRule(), code);

        // Не считаются: константа и статическое поле, вызов переопределяемого метода, параметр с именем поля,
        // неоткрытый метод и класс без прокси
        assertThat(violations).extracting(Violation::line).containsExactly(10, 13, 31, 45, 54);
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.CRITICAL);
        assertThat(violations.get(0).message())
                .contains("final метод 'find' в классе 'OrderService'", "@Transactional на методе 'save'",
                        "поля пусты", "'repository'", "NullPointerException");
        assertThat(violations.get(1).message()).contains("final метод 'findChecked'", "'repository'");
        assertThat(violations.get(2).message())
                .contains("@Async на final методе 'announce'", "выполнится в том же потоке")
                .doesNotContain("окажется пустым");
        assertThat(violations.get(3).message()).contains("final класс 'Mailer'", "@Async на методе 'send'");
        assertThat(violations.get(4).message()).contains("final метод 'url'", "@Validated на классе", "'url'");

        // @Transactional на final методе и в final классе - находки jr:59, здесь они не повторяются
        assertThat(RuleTests.lines(new TransactionalOnFinalRule(), code)).containsExactly(61, 66);
    }
}
