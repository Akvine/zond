package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.rules.performance.JmixLoadInLoopRule;
import ru.akvine.zond.rules.performance.JmixReferenceWithoutFetchPlanRule;
import ru.akvine.zond.rules.performance.JmixSaveInLoopRule;
import ru.akvine.zond.rules.security.JmixUnconstrainedDataManagerRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:386 - jr:389: работа с данными через DataManager в проектах на Jmix
 */
class JmixRulesTest {

    @TempDir
    Path dir;

    @Test
    void screensAndControllersMustNotBypassAccessRights() {
        List<Integer> lines = RuleTests.lines(new JmixUnconstrainedDataManagerRule(), """
                @ViewController("Order.list")
                class OrderListView extends StandardListView<Order> {
                    @Autowired
                    private UnconstrainedDataManager dataManager;
                }
                @RestController
                class OrderApi {
                    @Autowired
                    private DataManager dataManager;
                    List<Order> all() {
                        return dataManager.unconstrained().load(Order.class).all().list();
                    }
                }
                @Service
                class ImportService {
                    @Autowired
                    private UnconstrainedDataManager dataManager;
                }
                @RestController
                class SafeApi {
                    @Autowired
                    private DataManager dataManager;
                }
                class LegacyBrowse extends StandardLookup<Order> {
                    private UnconstrainedDataManager unconstrainedDataManager;
                }
                """);

        // Служебный код в обход прав работать может: пользователь им напрямую не управляет
        assertThat(lines).containsExactly(4, 11, 25);
    }

    @Test
    void loadingInLoopIsQueryPerElement() {
        List<Integer> lines = RuleTests.lines(new JmixLoadInLoopRule(), """
                class Sync {
                    private DataManager dataManager;
                    void each(List<UUID> ids) {
                        for (UUID id : ids) {
                            Order order = dataManager.load(Order.class).id(id).one();
                            process(order);
                        }
                    }
                    void once(List<UUID> ids) {
                        List<Order> orders = dataManager.load(Order.class).query("e.id in :ids").parameter("ids", ids).list();
                        for (Order order : dataManager.load(Order.class).all().list()) {
                            process(order);
                        }
                    }
                    void stream(List<UUID> ids) {
                        ids.forEach(id -> dataManager.load(Customer.class).id(id).optional());
                    }
                    void other(List<UUID> ids) {
                        for (UUID id : ids) {
                            cache.load(id).list();
                        }
                    }
                    void batches(List<List<UUID>> batches) {
                        for (List<UUID> batch : batches) {
                            dataManager.load(Order.class).query("e.id in :ids").parameter("ids", batch).list();
                        }
                    }
                    Optional<Order> first(List<UUID> ids) {
                        return ids.stream().map(id -> dataManager.load(Order.class).id(id).one()).findFirst();
                    }
                }
                """);

        // Источник цикла вычисляется один раз; load у постороннего объекта - не DataManager; цикл по пачкам
        // делает один запрос на пачку, а стрим с findFirst останавливается на первом элементе
        assertThat(lines).containsExactly(5, 16);
    }

    @Test
    void savingInLoopIsTransactionPerElement() {
        List<Integer> lines = RuleTests.lines(new JmixSaveInLoopRule(), """
                class Import {
                    private DataManager dataManager;
                    void each(List<Order> orders) {
                        for (Order order : orders) {
                            order.setDone(true);
                            dataManager.save(order);
                        }
                    }
                    void batch(List<Order> orders) {
                        SaveContext saveContext = new SaveContext();
                        for (Order order : orders) {
                            saveContext.saving(order);
                        }
                        dataManager.save(saveContext);
                    }
                    void chunks(List<List<Order>> chunks) {
                        for (List<Order> chunk : chunks) {
                            SaveContext saveContext = new SaveContext().saving(chunk);
                            dataManager.save(saveContext);
                        }
                    }
                    void removeEach(List<Order> orders) {
                        orders.forEach(order -> dataManager.remove(order));
                    }
                    void single(Order order) {
                        dataManager.save(order);
                    }
                }
                """);

        // Сохранение готовой пачки изменений по частям - уже не запись по одной
        assertThat(lines).containsExactly(6, 23);
    }

    @Test
    void referenceReadInLoopNeedsFetchPlan() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("Order.java", """
                        @JmixEntity
                        @Entity
                        class Order {
                            @Id
                            private UUID id;
                            @ManyToOne(fetch = FetchType.LAZY)
                            private Customer customer;
                            @Composition
                            @OneToMany(mappedBy = "order")
                            private List<OrderLine> lines;
                            private String number;
                        }
                        """)
                .source("Report.java", """
                        class Report {
                            private DataManager dataManager;
                            void lazy() {
                                List<Order> orders = dataManager.load(Order.class).all().list();
                                for (Order order : orders) {
                                    print(order.getNumber());
                                    print(order.getCustomer().getName());
                                }
                            }
                            void planned() {
                                List<Order> orders = dataManager.load(Order.class).all().fetchPlan("order-with-customer").list();
                                for (Order order : orders) {
                                    print(order.getCustomer().getName());
                                }
                            }
                            void inline() {
                                for (Order order : dataManager.load(Order.class).all().list()) {
                                    print(order.getLines().size());
                                }
                            }
                            void stream() {
                                List<Order> orders = dataManager.load(Order.class).all().list();
                                orders.stream().map(order -> order.getCustomer()).toList();
                            }
                            void plainFields() {
                                List<Order> orders = dataManager.load(Order.class).all().list();
                                for (Order order : orders) {
                                    print(order.getNumber());
                                }
                            }
                        }
                        """);

        // Обычное поле загружено вместе с сущностью; с планом выборки связь приходит тем же запросом
        assertThat(project.lines(new JmixReferenceWithoutFetchPlanRule())).containsExactly(
                "Report.java:18", "Report.java:23", "Report.java:7");
    }
}
