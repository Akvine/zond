package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.codesmell.ManualBeanLookupRule;
import ru.akvine.zond.rules.codesmell.RepositoryInControllerRule;
import ru.akvine.zond.rules.concurrency.AsyncOnCommonPoolRule;
import ru.akvine.zond.rules.concurrency.AsyncSelfInvocationRule;
import ru.akvine.zond.rules.logical.CascadeToParentRule;
import ru.akvine.zond.rules.logical.EntityCollectionReplacementRule;
import ru.akvine.zond.rules.logical.EventListenerInTransactionRule;
import ru.akvine.zond.rules.logical.ModifyingWithoutTransactionalRule;
import ru.akvine.zond.rules.logical.MoneyInFloatingPointRule;
import ru.akvine.zond.rules.performance.FindByIdIsPresentRule;
import ru.akvine.zond.rules.performance.IdentityGenerationRule;
import ru.akvine.zond.rules.performance.ListInManyToManyRule;
import ru.akvine.zond.rules.performance.OneToManyWithoutMappedByRule;
import ru.akvine.zond.rules.resources.HttpClientWithoutTimeoutRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:157 - jr:170: Spring и JPA
 */
class SpringMoreRulesTest {
    private static final String ENTITY = """
            @Entity
            class Order {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;
                @ManyToOne(cascade = CascadeType.ALL)
                private Customer customer;
                @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
                private List<Tag> tags;
                @OneToMany
                private List<Note> notes;
                @OneToMany(mappedBy = "order", orphanRemoval = true)
                private List<Item> items;
                private double totalPrice;
                private BigDecimal amount;
                private double weight;
                public void setItems(List<Item> items) {
                    this.items = items;
                }
                public void setNotes(List<Note> notes) {
                    this.notes = notes;
                }
            }
            """;

    @Test
    void asyncSelfInvocation() {
        assertThat(RuleTests.lines(new AsyncSelfInvocationRule(), """
                class Sample {
                    public void run() {
                        send();
                        other.send();
                    }
                    @Async
                    public void send() {}
                }
                """)).containsExactly(3);
    }

    @Test
    void httpClientWithoutTimeout() {
        HttpClientWithoutTimeoutRule rule = new HttpClientWithoutTimeoutRule();

        assertThat(RuleTests.lines(rule, """
                class Sample {
                    RestTemplate rest = new RestTemplate();
                    HttpClient client = HttpClient.newHttpClient();
                    RestTemplate custom = new RestTemplate(factory);
                }
                """)).containsExactly(2, 3);
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    RestTemplate rest = new RestTemplate();
                    void init() {
                        factory.setConnectTimeout(1000);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void modifyingWithoutTransactional() {
        assertThat(RuleTests.lines(new ModifyingWithoutTransactionalRule(), """
                interface OrderRepository {
                    @Modifying
                    @Query("update Order o set o.status = :status where o.id = :id")
                    void updateStatus(Long id, String status);
                    @Modifying
                    @Transactional
                    @Query("delete from Order o where o.id = :id")
                    void remove(Long id);
                }
                @Transactional
                interface SafeRepository {
                    @Modifying
                    void clean();
                }
                """)).containsExactly(2);
    }

    @Test
    void eventListenerInTransaction() {
        assertThat(RuleTests.lines(new EventListenerInTransactionRule(), """
                class Listener {
                    @EventListener
                    public void onCreated(OrderCreated event) {
                        mailSender.send(event.getEmail());
                    }
                    @EventListener
                    public void onPaid(OrderPaid event) {
                        orderRepository.save(event.getOrder());
                    }
                    @EventListener
                    public void onStarted(ApplicationReadyEvent event) {
                        cache.warmUp();
                    }
                    @TransactionalEventListener
                    public void afterCommit(OrderCreated event) {
                        mailSender.send(event.getEmail());
                    }
                }
                """)).containsExactly(2, 6);
    }

    @Test
    void asyncOnCommonPool() {
        assertThat(RuleTests.lines(new AsyncOnCommonPoolRule(), """
                class Sample {
                    void run(Executor executor) {
                        CompletableFuture.supplyAsync(() -> load());
                        CompletableFuture.runAsync(() -> work());
                        CompletableFuture.supplyAsync(() -> load(), executor);
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void repositoryInController() {
        assertThat(RuleTests.lines(new RepositoryInControllerRule(), """
                @RestController
                @RequiredArgsConstructor
                class OrderController {
                    private final OrderRepository orderRepository;
                    private final OrderService orderService;
                }
                @RestController
                class CleanController {
                    CleanController(OrderService service) {}
                }
                @Service
                class OrderService {
                    OrderService(OrderRepository repository) {}
                }
                """)).containsExactly(1);
    }

    @Test
    void findByIdIsPresent() {
        assertThat(RuleTests.lines(new FindByIdIsPresentRule(), """
                class Sample {
                    boolean run(Long id) {
                        boolean a = orderRepository.findById(id).isPresent();
                        boolean b = orderRepository.findById(id).isEmpty();
                        boolean ok1 = orderRepository.existsById(id);
                        boolean ok2 = cache.findById(id).isPresent();
                        return a;
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void manualBeanLookup() {
        assertThat(RuleTests.lines(new ManualBeanLookupRule(), """
                @Service
                class Sample {
                    void run() {
                        OrderService service = applicationContext.getBean(OrderService.class);
                        Object other = registry.getBean("x");
                    }
                }
                @Configuration
                class Config {
                    void init() {
                        context.getBean(OrderService.class);
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void cascadeToParent() {
        assertThat(RuleTests.lines(new CascadeToParentRule(), ENTITY)).containsExactly(6);
    }

    @Test
    void identityGeneration() {
        assertThat(RuleTests.lines(new IdentityGenerationRule(), ENTITY)).containsExactly(4);
    }

    @Test
    void oneToManyWithoutMappedBy() {
        assertThat(RuleTests.lines(new OneToManyWithoutMappedByRule(), ENTITY)).containsExactly(10);
    }

    @Test
    void listInManyToMany() {
        assertThat(RuleTests.lines(new ListInManyToManyRule(), ENTITY)).containsExactly(9);
    }

    @Test
    void moneyInFloatingPoint() {
        assertThat(RuleTests.lines(new MoneyInFloatingPointRule(), ENTITY)).containsExactly(14);
    }

    @Test
    void entityCollectionReplacement() {
        assertThat(RuleTests.lines(new EntityCollectionReplacementRule(), ENTITY)).containsExactly(18);
    }
}
