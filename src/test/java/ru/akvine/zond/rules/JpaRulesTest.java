package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.ManyToManyRule;
import ru.akvine.zond.rules.concurrency.EntityWithoutVersionRule;
import ru.akvine.zond.rules.logical.EntityEqualsWithRelationsRule;
import ru.akvine.zond.rules.logical.EntityFinalMethodRule;
import ru.akvine.zond.rules.logical.EntityToStringWithRelationsRule;
import ru.akvine.zond.rules.logical.EntityWithDataRule;
import ru.akvine.zond.rules.logical.EntityWithoutIdRule;
import ru.akvine.zond.rules.logical.EntityWithoutNoArgsConstructorRule;
import ru.akvine.zond.rules.logical.EnumeratedOrdinalRule;
import ru.akvine.zond.rules.logical.LazyAccessOutsideTransactionRule;
import ru.akvine.zond.rules.performance.EagerFetchRule;
import ru.akvine.zond.rules.performance.FindAllWithoutPagingRule;
import ru.akvine.zond.rules.performance.RelationContainsInLoopRule;
import ru.akvine.zond.rules.performance.RelationSizeInLoopRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:80 - jr:86 и jr:96 - jr:102: JPA-сущности и работа с репозиториями
 */
class JpaRulesTest {
    private static final String RELATIONS = """
            @Entity
            class Order {
                @ManyToMany
                private Set<Tag> tags;
                @ManyToOne
                private User user;
                @OneToOne(fetch = FetchType.LAZY)
                private Invoice invoice;
                @OneToMany(fetch = FetchType.EAGER)
                private List<Item> items;
                @OneToMany
                private List<Note> notes;
            }
            """;

    private static final String RELATION_ACCESS = """
            class Sample {
                void run(List<Order> orders, Order outer, Item target) {
                    for (Order order : orders) {
                        int count = order.getItems().size();
                        boolean has = order.getItems().contains(target);
                        int same = outer.getItems().size();
                    }
                    orders.forEach(order -> total += order.getItems().size());
                    int once = outer.getItems().size();
                }
                private OrderRepository orderRepository;
            }
            """;

    @Test
    void relationAccessIsIgnoredOutsidePersistenceCode() {
        assertThat(RuleTests.lines(new RelationSizeInLoopRule(), """
                class Sample {
                    void run(List<MethodCallExpr> calls) {
                        calls.forEach(call -> total += call.getArguments().size());
                    }
                }
                """)).isEmpty();
    }

    @Test
    void entityWithData() {
        assertThat(RuleTests.lines(new EntityWithDataRule(), """
                @Data
                @Entity
                class User {
                }
                @Data
                class Dto {
                }
                @Getter
                @Entity
                class Order {
                }
                """)).containsExactly(1);
    }

    @Test
    void entityEqualsWithRelations() {
        List<Violation> violations = RuleTests.check(new EntityEqualsWithRelationsRule(), """
                @Entity
                class Order {
                    @Id
                    private Long id;
                    @OneToMany
                    private List<Item> items;
                    @ManyToOne
                    private User user;
                    public boolean equals(Object other) {
                        return id.equals(((Order) other).id) && items.equals(((Order) other).items);
                    }
                    public int hashCode() {
                        return Objects.hash(id, getUser());
                    }
                }
                @Entity
                @EqualsAndHashCode
                class Item {
                    @ManyToOne
                    private Order order;
                }
                @Entity
                @EqualsAndHashCode(onlyExplicitlyIncluded = true)
                class Safe {
                    @ManyToOne
                    private Order order;
                    public String toString() { return "Safe"; }
                }
                @Entity
                @EqualsAndHashCode
                class Excluded {
                    @EqualsAndHashCode.Exclude
                    @ManyToOne
                    private Order order;
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(9, 12, 17);
        assertThat(violations.get(0).message()).contains("equals()", "(items)");
        assertThat(violations.get(1).message()).contains("hashCode()", "(user)");
        assertThat(violations.get(2).message()).contains("@EqualsAndHashCode", "(order)");
    }

    @Test
    void entityToStringWithRelations() {
        assertThat(RuleTests.lines(new EntityToStringWithRelationsRule(), """
                @Entity
                class Order {
                    @OneToMany
                    private List<Item> items;
                    private String name;
                    public String toString() {
                        return "Order{name=" + name + ", items=" + items + "}";
                    }
                }
                @Entity
                @ToString
                class Item {
                    @ManyToOne
                    private Order order;
                }
                @Entity
                class Clean {
                    @OneToMany
                    private List<Item> items;
                    public String toString() {
                        return "Clean";
                    }
                }
                """)).containsExactly(6, 11);
    }

    @Test
    void lazyAccessOutsideTransaction() {
        assertThat(RuleTests.lines(new LazyAccessOutsideTransactionRule(), """
                @Service
                class OrderService {
                    public int count(Long id) {
                        Order order = orderRepository.findById(id).orElseThrow();
                        for (Item item : order.getItems()) {
                            use(item);
                        }
                        return order.getItems().size();
                    }
                    @Transactional
                    public int safe(Long id) {
                        Order order = orderRepository.findById(id).orElseThrow();
                        return order.getItems().size();
                    }
                    public String name(Long id) {
                        Order loaded = orderRepository.findById(id).orElseThrow();
                        OrderDto dto = mapper.toDto(loaded);
                        return loaded.getName() + dto.getItems().size();
                    }
                }
                """)).containsExactly(5, 8);
    }

    @Test
    void enumeratedOrdinal() {
        assertThat(RuleTests.lines(new EnumeratedOrdinalRule(), """
                @Entity
                class Order {
                    @Enumerated
                    private Status first;
                    @Enumerated(EnumType.ORDINAL)
                    private Status second;
                    @Enumerated(EnumType.STRING)
                    private Status third;
                }
                """)).containsExactly(3, 5);
    }

    @Test
    void entityWithoutVersion() {
        assertThat(RuleTests.lines(new EntityWithoutVersionRule(), """
                @Entity
                class Plain {
                    @Id
                    private Long id;
                }
                @Entity
                class Versioned {
                    @Version
                    private Long version;
                }
                @Entity
                class Child extends Base {
                }
                class NotEntity {
                }
                """)).containsExactly(1);
    }

    @Test
    void findAllWithoutPaging() {
        assertThat(RuleTests.lines(new FindAllWithoutPagingRule(), """
                class Sample {
                    void run(Pageable pageable) {
                        List<User> all = userRepository.findAll();
                        Page<User> page = userRepository.findAll(pageable);
                        List<String> matches = matcher.findAll();
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void relationSizeInLoop() {
        assertThat(RuleTests.lines(new RelationSizeInLoopRule(), RELATION_ACCESS)).containsExactly(4, 8);
    }

    @Test
    void relationContainsInLoop() {
        assertThat(RuleTests.lines(new RelationContainsInLoopRule(), RELATION_ACCESS)).containsExactly(5);
    }

    @Test
    void manyToMany() {
        assertThat(RuleTests.lines(new ManyToManyRule(), RELATIONS)).containsExactly(3);
    }

    @Test
    void eagerFetch() {
        List<Violation> violations = RuleTests.check(new EagerFetchRule(), RELATIONS);

        assertThat(violations).extracting(Violation::line).containsExactly(5, 9);
        assertThat(violations.get(0).message()).contains("@ManyToOne без fetch");
        assertThat(violations.get(1).message()).contains("@OneToMany с FetchType.EAGER");
    }

    @Test
    void entityFinalMethod() {
        assertThat(RuleTests.lines(new EntityFinalMethodRule(), """
                @Entity
                final class Closed {
                    public final Long getId() { return null; }
                }
                @Entity
                class Open {
                    public final Long getId() { return null; }
                    public Long getVersion() { return null; }
                    private final void helper() {}
                }
                final class NotEntity {
                }
                """)).containsExactly(1, 7);
    }

    @Test
    void entityWithoutNoArgsConstructor() {
        assertThat(RuleTests.lines(new EntityWithoutNoArgsConstructorRule(), """
                @Entity
                class WithArgs {
                    WithArgs(Long id) {}
                }
                @Entity
                class Both {
                    protected Both() {}
                    Both(Long id) {}
                }
                @Entity
                class Default {
                }
                @Entity
                @AllArgsConstructor
                class LombokArgs {
                }
                @Entity
                @NoArgsConstructor
                @AllArgsConstructor
                class LombokBoth {
                }
                """)).containsExactly(1, 13);
    }

    @Test
    void entityWithoutId() {
        assertThat(RuleTests.lines(new EntityWithoutIdRule(), """
                @Entity
                class NoId {
                    private String name;
                }
                @Entity
                class WithId {
                    @Id
                    private Long id;
                }
                @Entity
                class Child extends Base {
                }
                @Entity
                @IdClass(Key.class)
                class Composite {
                }
                """)).containsExactly(1);
    }
}
