package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.ConstantHelperPerCallRule;
import ru.akvine.zond.rules.performance.LazyRelationInMappingRule;
import ru.akvine.zond.rules.performance.PageWithoutTotalRule;
import ru.akvine.zond.rules.performance.RepeatedComputationRule;
import ru.akvine.zond.rules.performance.RepositoryCountForExistenceRule;
import ru.akvine.zond.rules.performance.SequenceAllocationSizeOneRule;
import ru.akvine.zond.rules.streams.IntermediateCollectionRule;
import ru.akvine.zond.rules.streams.SortBeforeUnorderedTerminalRule;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:502 - jr:508: работа, результат которой выбрасывают или которую делают повторно
 */
class ExtraWorkRulesTest {
    private static final String ORDER_ENTITY = """
            @Entity
            class OrderEntity {
                @Id
                private Long id;
                @ManyToOne(fetch = FetchType.LAZY)
                private ClientEntity client;
                @OneToMany(mappedBy = "order")
                private List<ItemEntity> items;
                @ManyToOne
                private ShopEntity shop;
                @BatchSize(size = 50)
                @OneToMany(mappedBy = "order")
                private List<NoteEntity> notes;
            }
            """;
    private static final String ORDER_MODEL = """
            class OrderModel {
                private final Long id;
                private final String clientName;
                OrderModel(OrderEntity order) {
                    this.id = order.getId();
                    this.clientName = order.getClient().getName();
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void converterReadsLazyRelationOfEveryElement() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("OrderEntity.java", ORDER_ENTITY)
                .source("OrderModel.java", ORDER_MODEL)
                .source("ShortModel.java", """
                        class ShortModel {
                            ShortModel(OrderEntity order) {
                                this.id = order.getId();
                                this.clientId = order.getClient().getId();
                                this.shop = order.getShop().getName();
                                this.notes = order.getNotes().size();
                            }
                        }
                        """)
                .source("OrderConverter.java", """
                        @Component
                        class OrderConverter {
                            OrderDto toDto(OrderEntity order) {
                                OrderDto dto = new OrderDto(order.getId());
                                fill(dto, order);
                                return dto;
                            }
                            private void fill(OrderDto dto, OrderEntity order) {
                                dto.setItemCount(order.getItems().size());
                            }
                            OrderDto toShort(OrderEntity order) {
                                return new OrderDto(order.getId());
                            }
                        }
                        """)
                .source("OrderService.java", """
                        @Service
                        class OrderService {
                            private final OrderRepository orderRepository;
                            private final OrderConverter orderConverter;
                            List<OrderModel> models() {
                                return orderRepository.findAll().stream().map(OrderModel::new).toList();
                            }
                            List<ShortModel> shorts() {
                                return orderRepository.findAll().stream().map(ShortModel::new).toList();
                            }
                            List<OrderDto> dtos(List<OrderEntity> orders) {
                                return orders.stream().map(orderConverter::toDto).toList();
                            }
                            List<String> names(List<OrderEntity> orders) {
                                List<String> names = new ArrayList<>();
                                for (OrderEntity order : orders) {
                                    names.add(orderConverter.toShort(order).title());
                                    names.add(order.getClient().getName());
                                }
                                return names;
                            }
                            OrderModel one(Long id) {
                                return new OrderModel(orderRepository.getReferenceById(id));
                            }
                        }
                        """);
        List<Violation> violations = project.check(new LazyRelationInMappingRule());

        // getId() прокси не раскрывает; shop загружается сразу, notes - пачкой; одна сущность - не список
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("OrderService.java:6", "OrderService.java:12", "OrderService.java:18");
        assertThat(violations.get(0).message()).contains("'OrderModel::new'", "'OrderEntity.client'", "N+1");
        assertThat(violations.get(1).message()).contains("'OrderEntity.items'");
        assertThat(violations).extracting(Violation::confidenceOrDefault).containsOnly(Confidence.PROBABLE);
    }

    @Test
    void relationLoadedInAdvanceIsNotReported() {
        String service = """
                @Service
                class OrderService {
                    private final OrderRepository orderRepository;
                    List<OrderModel> models() {
                        return orderRepository.findAll().stream().map(OrderModel::new).toList();
                    }
                }
                """;
        ProjectFixture withGraph = new ProjectFixture(dir.resolve("graph"))
                .source("OrderEntity.java", ORDER_ENTITY)
                .source("OrderModel.java", ORDER_MODEL)
                .source("OrderService.java", service)
                .source("OrderRepository.java", """
                        interface OrderRepository extends JpaRepository<OrderEntity, Long> {
                            @EntityGraph(attributePaths = "client")
                            List<OrderEntity> findAll();
                        }
                        """);
        ProjectFixture withBatches = new ProjectFixture(dir.resolve("batch"))
                .source("OrderEntity.java", ORDER_ENTITY)
                .source("OrderModel.java", ORDER_MODEL)
                .source("OrderService.java", service)
                .write("src/main/resources/application.properties",
                        "spring.jpa.properties.hibernate.default_batch_fetch_size=100\n");

        ProjectFixture jmix = new ProjectFixture(dir.resolve("jmix"))
                .source("OrderEntity.java", "@JmixEntity\n" + ORDER_ENTITY)
                .source("OrderModel.java", ORDER_MODEL)
                .source("OrderService.java", service);

        // Сущности Jmix загружают по планам выборки: правило о них не судит
        assertThat(jmix.check(new LazyRelationInMappingRule())).isEmpty();
        // Каким запросом загружен список, по месту преобразования не видно - остается подозрение
        assertThat(withGraph.check(new LazyRelationInMappingRule()))
                .extracting(Violation::confidenceOrDefault).containsExactly(Confidence.SUSPICION);
        assertThat(withBatches.check(new LazyRelationInMappingRule())).isEmpty();
    }

    @Test
    void sameValueIsComputedTwiceInOneExpression() {
        List<Violation> violations = RuleTests.check(new RepeatedComputationRule(), """
                @Service
                class Checker {
                    private final OrderRepository orderRepository;
                    boolean check(String key, File dir, Iterator<String> lines) throws IOException {
                        boolean api = key.toLowerCase().contains("api") || key.toLowerCase().contains("rest");
                        String rest = dir.getCanonicalPath().substring(dir.getCanonicalPath().lastIndexOf('/'));
                        Order order = orderRepository.findById(1L).isPresent() ? orderRepository.findById(1L).get() : null;
                        String once = key.trim().isEmpty() ? "" : key.toUpperCase();
                        String either = api ? key.trim() : key.trim().toLowerCase();
                        String pair = lines.next().trim() + lines.next().trim();
                        return api && order != null;
                    }
                }
                """);

        // Ветки ?: выполняются по одной; next() каждый раз возвращает новое значение
        assertThat(violations).extracting(Violation::line).containsExactly(5, 6, 7);
        assertThat(violations.get(0).message()).contains("'key.toLowerCase()'", "2 раза", "в переменную");
    }

    @Test
    void constantTemplateIsParsedOnEveryCall() {
        assertThat(RuleTests.lines(new ConstantHelperPerCallRule(), """
                class Dates {
                    private static final Pattern DIGITS = Pattern.compile("\\\\d+");
                    private final DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm");
                    private DateTimeFormatter date;
                    String format(LocalDate value) {
                        return value.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
                    }
                    boolean isNumber(String text) {
                        return Pattern.compile("\\\\d+", Pattern.CASE_INSENSITIVE).matcher(text).matches();
                    }
                    String custom(LocalDate value, String pattern) {
                        return value.format(DateTimeFormatter.ofPattern(pattern));
                    }
                    @PostConstruct
                    void init() {
                        date = DateTimeFormatter.ofPattern("dd.MM");
                    }
                    Module initJavaTimeModule() {
                        return new JavaTimeModule()
                                .addSerializer(new LocalDateSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                    }
                    Module dates() {
                        return module.addSerializer(new LocalDateSerializer(DateTimeFormatter.ofPattern("dd.MM")));
                    }
                }
                """)).containsExactly(6, 9);
    }

    @Test
    void countQueryAnswersExistenceQuestion() {
        assertThat(RuleTests.lines(new RepositoryCountForExistenceRule(), """
                @Service
                class Orders {
                    private final OrderRepository orderRepository;
                    boolean has(Long clientId) {
                        return orderRepository.countByClientId(clientId) > 0;
                    }
                    boolean isEmpty() {
                        return 0 == orderRepository.count();
                    }
                    boolean single(Long clientId) {
                        return orderRepository.countByClientId(clientId) == 1;
                    }
                    boolean any(List<Order> orders) {
                        return orders.stream().count() > 0;
                    }
                }
                """)).containsExactly(5, 8);
    }

    @Test
    void pageIsLoadedOnlyForContent() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OrderRepository.java", """
                interface OrderRepository extends JpaRepository<Order, Long> {
                    Page<Order> findByStatus(String status, Pageable pageable);
                    Slice<Order> findByOwner(String owner, Pageable pageable);
                }
                """);
        files.put("OrderService.java", """
                @Service
                class OrderService {
                    private final OrderRepository orderRepository;
                    List<Order> byStatus(String status, Pageable pageable) {
                        return orderRepository.findByStatus(status, pageable).getContent();
                    }
                    List<Order> byOwner(String owner, Pageable pageable) {
                        return orderRepository.findByOwner(owner, pageable).getContent();
                    }
                    List<Order> all(int page) {
                        return orderRepository.findAll(PageRequest.of(page, 20)).getContent();
                    }
                    Page<Order> page(String status, Pageable pageable) {
                        return orderRepository.findByStatus(status, pageable);
                    }
                }
                """);

        // Slice второго запроса не делает; страница, которую отдают целиком, нужна вместе с итогом
        assertThat(RuleTests.checkProject(new PageWithoutTotalRule(), files))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("OrderService.java:5", "OrderService.java:11");
    }

    @Test
    void sortingIsWastedWhenOrderIsNotUsed() {
        assertThat(RuleTests.lines(new SortBeforeUnorderedTerminalRule(), """
                class Stats {
                    long count(List<String> names) {
                        return names.stream().sorted().filter(name -> name.length() > 3).count();
                    }
                    Set<String> unique(List<String> names) {
                        return names.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toSet());
                    }
                    Set<String> top(List<String> names) {
                        return names.stream().sorted().limit(3).collect(Collectors.toSet());
                    }
                    Optional<String> first(List<String> names) {
                        return names.stream().sorted().findFirst();
                    }
                    List<String> ordered(List<String> names) {
                        return names.stream().sorted().toList();
                    }
                }
                """)).containsExactly(3, 6);
    }

    @Test
    void sequenceIsAskedForEveryIdentifier() {
        SequenceAllocationSizeOneRule rule = new SequenceAllocationSizeOneRule();

        assertThat(RuleTests.lines(rule, """
                @Entity
                class Advert {
                    @Id
                    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "advertSeq")
                    @SequenceGenerator(name = "advertSeq", sequenceName = "SEQ_ADVERT", allocationSize = 1)
                    private Long id;
                }
                @Entity
                class Card {
                    @Id
                    @SequenceGenerator(name = "cardSeq", sequenceName = "SEQ_CARD", allocationSize = 1000)
                    private Long id;
                }
                """)).containsExactly(5);
        assertThat(rule.confidence()).isEqualTo(Confidence.PROBABLE);
    }

    @Test
    void listIsCollectedForSingleQuestion() {
        List<Violation> violations = RuleTests.check(new IntermediateCollectionRule(), """
                class Names {
                    int blank(List<String> names) {
                        return names.stream().filter(String::isBlank).collect(Collectors.toList()).size();
                    }
                    boolean has(List<String> names, String name) {
                        return names.stream().map(String::trim).toList().contains(name);
                    }
                    String first(List<String> names) {
                        return names.stream().sorted().collect(Collectors.toList()).get(0);
                    }
                    String third(List<String> names) {
                        return names.stream().sorted().toList().get(2);
                    }
                    List<String> all(List<String> names) {
                        return names.stream().map(String::trim).collect(Collectors.toList());
                    }
                    int size(Tuple tuple) {
                        return tuple.toList().size();
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 6, 9);
        assertThat(violations.get(0).message()).contains("size(...)", "count()");
    }
}
