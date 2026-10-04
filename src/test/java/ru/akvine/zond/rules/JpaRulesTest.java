package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.CheckManyToManyRule;
import ru.akvine.zond.rules.concurrency.CheckEntityWithoutVersionRule;
import ru.akvine.zond.rules.logical.CheckEntityEqualsWithRelationsRule;
import ru.akvine.zond.rules.logical.CheckEntityFinalMethodRule;
import ru.akvine.zond.rules.logical.CheckEntityToStringWithRelationsRule;
import ru.akvine.zond.rules.logical.CheckEntityWithDataRule;
import ru.akvine.zond.rules.logical.CheckEntityWithoutIdRule;
import ru.akvine.zond.rules.logical.CheckEntityWithoutNoArgsConstructorRule;
import ru.akvine.zond.rules.logical.CheckEnumeratedOrdinalRule;
import ru.akvine.zond.rules.logical.CheckLazyAccessOutsideTransactionRule;
import ru.akvine.zond.rules.performance.CheckEagerFetchRule;
import ru.akvine.zond.rules.performance.CheckFindAllWithoutPagingRule;
import ru.akvine.zond.rules.performance.CheckRelationContainsInLoopRule;
import ru.akvine.zond.rules.performance.CheckRelationSizeInLoopRule;

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
        assertThat(RuleTests.lines(new CheckRelationSizeInLoopRule(), """
                class Sample {
                    void run(List<MethodCallExpr> calls) {
                        calls.forEach(call -> total += call.getArguments().size());
                    }
                }
                """)).isEmpty();
    }

    @Test
    void entityWithData() {
        assertThat(RuleTests.lines(new CheckEntityWithDataRule(), """
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
        List<Violation> violations = RuleTests.check(new CheckEntityEqualsWithRelationsRule(), """
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
        assertThat(RuleTests.lines(new CheckEntityToStringWithRelationsRule(), """
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
        assertThat(RuleTests.lines(new CheckLazyAccessOutsideTransactionRule(), """
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
        assertThat(RuleTests.lines(new CheckEnumeratedOrdinalRule(), """
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
        assertThat(RuleTests.lines(new CheckEntityWithoutVersionRule(), """
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
        assertThat(RuleTests.lines(new CheckFindAllWithoutPagingRule(), """
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
        assertThat(RuleTests.lines(new CheckRelationSizeInLoopRule(), RELATION_ACCESS)).containsExactly(4, 8);
    }

    @Test
    void relationContainsInLoop() {
        assertThat(RuleTests.lines(new CheckRelationContainsInLoopRule(), RELATION_ACCESS)).containsExactly(5);
    }

    @Test
    void manyToMany() {
        assertThat(RuleTests.lines(new CheckManyToManyRule(), RELATIONS)).containsExactly(3);
    }

    @Test
    void eagerFetch() {
        List<Violation> violations = RuleTests.check(new CheckEagerFetchRule(), RELATIONS);

        assertThat(violations).extracting(Violation::line).containsExactly(5, 9);
        assertThat(violations.get(0).message()).contains("@ManyToOne без fetch");
        assertThat(violations.get(1).message()).contains("@OneToMany с FetchType.EAGER");
    }

    @Test
    void entityFinalMethod() {
        assertThat(RuleTests.lines(new CheckEntityFinalMethodRule(), """
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
        assertThat(RuleTests.lines(new CheckEntityWithoutNoArgsConstructorRule(), """
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
        assertThat(RuleTests.lines(new CheckEntityWithoutIdRule(), """
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
