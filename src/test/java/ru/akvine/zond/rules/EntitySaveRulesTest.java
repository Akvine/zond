package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.EntityChangeWithoutSaveRule;
import ru.akvine.zond.rules.performance.ManualIdEntitySaveRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:463 и jr:464: лишний SELECT при сохранении сущности с заданным id и изменение без сохранения
 */
class EntitySaveRulesTest {
    private static final String COUNTRY = """
            @Entity
            class Country {
                @Id
                private String code;
                private String name;
            }
            """;
    private static final String ORDER = """
            @Entity
            class Order extends BaseEntity {
                private String title;
                private String status;
            }
            """;
    private static final String BASE_ENTITY = """
            @MappedSuperclass
            class BaseEntity {
                @Id
                @GeneratedValue(strategy = GenerationType.SEQUENCE)
                private Long id;
            }
            """;
    private static final String DEVICE = """
            @Entity
            class Device implements Persistable<UUID> {
                @Id
                private UUID id;
                @Transient
                private boolean created = true;
                @Override
                public boolean isNew() {
                    return created;
                }
            }
            """;
    private static final String DOCUMENT = """
            @Entity
            class Document {
                @Id
                private UUID id;
                @Version
                private Long version;
            }
            """;

    @TempDir
    Path dir;

    @Test
    void newEntityWithAssignedIdIsMerged() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("Country.java", COUNTRY)
                .source("BaseEntity.java", BASE_ENTITY)
                .source("Order.java", ORDER)
                .source("Device.java", DEVICE)
                .source("Document.java", DOCUMENT)
                .source("ImportService.java", """
                        class ImportService {
                            private CountryRepository countryRepository;
                            private OrderRepository orderRepository;
                            private DeviceRepository deviceRepository;
                            private DocumentRepository documentRepository;
                            void single(String code) {
                                countryRepository.save(new Country(code, "Россия"));
                            }
                            void batch(List<CountryRow> rows) {
                                for (CountryRow row : rows) {
                                    Country country = Country.builder().code(row.code()).name(row.name()).build();
                                    countryRepository.save(country);
                                }
                            }
                            void generated(String title) {
                                Order order = new Order(title);
                                orderRepository.save(order);
                            }
                            void generatedButAssigned(Long id, String title) {
                                Order order = new Order(title);
                                order.setId(id);
                                orderRepository.save(order);
                                orderRepository.save(Order.builder().id(id).title(title).build());
                            }
                            void update(String code, String name) {
                                Country country = countryRepository.findById(code).orElseThrow();
                                country.setName(name);
                                countryRepository.save(country);
                            }
                            void decidesItself(UUID id) {
                                deviceRepository.save(new Device(id));
                                documentRepository.save(new Document(id));
                            }
                            void notRepository(Cache cache, String code) {
                                cache.save(new Country(code, "Россия"));
                            }
                        }
                        """);
        ManualIdEntitySaveRule rule = new ManualIdEntitySaveRule();

        // Не считаются: id от генератора, запись из базы, сущность с Persistable либо с @Version
        List<Violation> violations = rule.checkProject(project.context().sources());
        assertThat(violations).extracting(Violation::line).containsExactly(7, 12, 22, 23);
        assertThat(violations.get(0).message())
                .contains("Новая сущность 'Country'", "без @GeneratedValue", "лишний SELECT", "Persistable<ID>");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        // В цикле лишний запрос уходит на каждую запись
        assertThat(violations.get(1).message()).contains("на каждой итерации цикла");
        assertThat(violations.get(1).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(violations.get(2).message()).contains("id задан вручную ('order.setId(...)')", "6.6");
        assertThat(violations.get(3).message()).contains("'id(...)'");
    }

    @Test
    void changeOutsideTransactionNeedsSave() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("BaseEntity.java", BASE_ENTITY)
                .source("Order.java", ORDER)
                .source("OrderService.java", """
                        class OrderService {
                            private OrderRepository orderRepository;
                            private OrderMapper orderMapper;
                            private TransactionTemplate transactionTemplate;
                            public OrderDto rename(Long id, String title) {
                                Order order = orderRepository.findById(id).orElseThrow();
                                order.setTitle(title);
                                log.info("renamed {}", order);
                                return orderMapper.toDto(order);
                            }
                            public void cancel(Long id) {
                                Order order = orderRepository.getReferenceById(id);
                                order.cancel();
                            }
                            public void saved(Long id, String title) {
                                Order order = orderRepository.findById(id).orElseThrow();
                                order.setTitle(title);
                                orderRepository.save(order);
                            }
                            @Transactional
                            public void managed(Long id, String title) {
                                Order order = orderRepository.findById(id).orElseThrow();
                                order.setTitle(title);
                            }
                            public Order returned(Long id, String title) {
                                Order order = orderRepository.findById(id).orElseThrow();
                                order.setTitle(title);
                                return order;
                            }
                            public void handedOver(Long id) {
                                Order order = orderRepository.findById(id).orElseThrow();
                                order.setStatus("SENT");
                                publisher.send(order);
                            }
                            public void onlyRead(Long id) {
                                Order order = orderRepository.findById(id).orElseThrow();
                                order.validate();
                                String title = order.getTitle();
                            }
                            public void manualTransaction(Long id) {
                                transactionTemplate.executeWithoutResult(status -> {
                                    Order order = orderRepository.findById(id).orElseThrow();
                                    order.setStatus("DONE");
                                });
                            }
                            void calledInTransaction(Long id) {
                                Order order = orderRepository.findById(id).orElseThrow();
                                order.setStatus("PAID");
                            }
                            public void created(String title) {
                                Order order = new Order(title);
                                order.setStatus("NEW");
                            }
                        }
                        """)
                .source("PaymentService.java", """
                        class PaymentService {
                            private OrderService orderService;
                            @Transactional
                            public void pay(Long id) {
                                orderService.calledInTransaction(id);
                            }
                        }
                        """);
        EntityChangeWithoutSaveRule rule = new EntityChangeWithoutSaveRule();

        // Не считаются: явный save, метод в транзакции (своей, вызывающего либо TransactionTemplate), сущность,
        // которую вернули или отдали другому бину, и чтение без изменения
        List<Violation> violations = rule.checkProject(project.context().sources());
        assertThat(violations).extracting(Violation::line).containsExactly(7, 13);
        assertThat(violations.get(0).message())
                .contains("Сущность 'order'", "в методе 'rename' без транзакции", "'order.setTitle(...)'",
                        "repository.save(order)", "@Transactional");
    }
}
