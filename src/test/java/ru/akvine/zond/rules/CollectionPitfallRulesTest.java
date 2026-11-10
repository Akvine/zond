package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.OptionalOfCollectionRule;
import ru.akvine.zond.rules.logical.NullInImmutableCollectionRule;
import ru.akvine.zond.rules.performance.ListRemoveInLoopRule;
import ru.akvine.zond.rules.streams.AggregatesInSeparatePassesRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:465, jr:466, jr:469, jr:470 и массив примитивов в List.of(): ловушки коллекций и стримов
 */
class CollectionPitfallRulesTest {

    @Test
    void immutableCollectionRejectsNull() {
        List<Violation> violations = RuleTests.check(new NullInImmutableCollectionRule(), """
                class Sample {
                    private static final List<String> ALLOWED = List.of("NEW", "PAID");
                    private static final Map<String, Integer> LIMITS = Map.of("NEW", 1);
                    private List<String> mutable = List.of("A");
                    List<String> broken() {
                        return List.of("A", null);
                    }
                    boolean literal() {
                        return ALLOWED.contains(null) || Set.of("A").contains(null);
                    }
                    boolean nullable(@Nullable String status) {
                        return ALLOWED.contains(status);
                    }
                    boolean optional(@RequestParam(required = false) String status) {
                        return LIMITS.containsKey(status);
                    }
                    boolean fromOptional(Optional<String> found) {
                        String status = found.orElse(null);
                        return ALLOWED.contains(status) || ALLOWED.contains(found.orElse(null));
                    }
                    boolean checked(@Nullable String status) {
                        return status != null && ALLOWED.contains(status);
                    }
                    boolean plain(String status) {
                        return ALLOWED.contains(status);
                    }
                    boolean arrayList(@Nullable String status) {
                        List<String> values = new ArrayList<>(ALLOWED);
                        return values.contains(null) || Arrays.asList("A").contains(null);
                    }
                    boolean reassigned() {
                        mutable = new ArrayList<>();
                        return mutable.contains(null);
                    }
                }
                """);

        // Параметр без пометок, значение после проверки на null и обычные коллекции не считаются
        assertThat(violations).extracting(Violation::line).containsExactly(6, 9, 9, 12, 15, 19, 19);
        assertThat(violations.get(0).message()).contains("null в 'List.of(...)'", "NullPointerException");
        assertThat(violations.get(1).message())
                .contains("'ALLOWED.contains(null)'", "из List.of(...)", "а не возвращает false");
        assertThat(violations.get(1).errorLevel()).isEqualTo(ErrorLevel.CRITICAL);
        // Значение только может оказаться null - находка мягче
        assertThat(violations.get(3).message()).contains("параметр 'status' объявлен необязательным");
        assertThat(violations.get(3).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(violations.get(5).message()).contains("в 'status' может лежать null");
    }

    @Test
    void removeInLoopIsQuadratic() {
        List<Violation> violations = RuleTests.check(new ListRemoveInLoopRule(), """
                class Sample {
                    void byValue(List<Order> orders, List<Order> cancelled) {
                        for (Order order : cancelled) {
                            orders.remove(order);
                        }
                    }
                    void byIndex(ArrayList<Order> orders) {
                        for (int i = orders.size() - 1; i >= 0; i--) {
                            if (orders.get(i).isCancelled()) {
                                orders.remove(i);
                            }
                        }
                    }
                    void byIterator(List<Order> orders) {
                        Iterator<Order> iterator = orders.iterator();
                        while (iterator.hasNext()) {
                            if (iterator.next().isCancelled()) {
                                iterator.remove();
                            }
                        }
                    }
                    void inLambda(List<Order> orders, List<Order> cancelled) {
                        cancelled.forEach(order -> orders.remove(order));
                    }
                    void single(List<Order> orders, Long id) {
                        for (Order order : List.copyOf(orders)) {
                            if (order.getId().equals(id)) {
                                orders.remove(order);
                                break;
                            }
                        }
                    }
                    void fromSet(Set<Order> orders, List<Order> cancelled, Map<Long, Order> byId) {
                        for (Order order : cancelled) {
                            orders.remove(order);
                            byId.remove(order.getId());
                        }
                    }
                    void linked(LinkedList<Order> orders) {
                        Iterator<Order> iterator = orders.iterator();
                        while (iterator.hasNext()) {
                            if (iterator.next().isCancelled()) {
                                iterator.remove();
                            }
                        }
                    }
                    void stack(List<Order> orders) {
                        while (!orders.isEmpty()) {
                            process(orders.remove(orders.size() - 1));
                        }
                    }
                    void good(List<Order> orders) {
                        orders.removeIf(Order::isCancelled);
                    }
                }
                """);

        // Множество, карта, LinkedList через итератор, удаление с конца и одно удаление с выходом из цикла - не O(n²)
        assertThat(violations).extracting(Violation::line).containsExactly(4, 10, 18, 23);
        assertThat(violations.get(0).message()).contains("'orders.remove(order)' в цикле", "O(n²)", "orders.removeIf(...)");
        assertThat(violations.get(2).message()).contains("'iterator.remove()' в цикле по списку 'orders'");
    }

    @Test
    void optionalAroundCollectionAddsThirdState() {
        List<Violation> violations = RuleTests.check(new OptionalOfCollectionRule(), """
                interface OrderRepository extends JpaRepository<Order, Long> {
                    Optional<List<Order>> findByStatus(String status);
                    Optional<Order> findByNumber(String number);
                    List<Order> findByCustomerId(Long id);
                }
                class OrderService implements Loader {
                    Optional<Map<String, Order>> index() {
                        return Optional.empty();
                    }
                    Optional<byte[]> content() {
                        return Optional.empty();
                    }
                    @Override
                    public Optional<Set<Order>> load() {
                        return Optional.empty();
                    }
                    Optional<String> name() {
                        return Optional.empty();
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(2, 7, 10);
        assertThat(violations.get(0).message())
                .contains("'findByStatus' возвращает Optional<List<Order>>", "три состояния", "возвращайте List<Order>");
        assertThat(violations.get(2).message()).contains("пустой массив");
    }

    @Test
    void aggregatesAreCollectedInOnePass() {
        List<Violation> violations = RuleTests.check(new AggregatesInSeparatePassesRule(), """
                class Report {
                    Range range(List<Order> orders) {
                        int min = orders.stream().mapToInt(Order::getTotal).min().orElse(0);
                        int max = orders.stream().mapToInt(Order::getTotal).max().orElse(0);
                        return new Range(min, max);
                    }
                    Totals totals(List<Order> orders) {
                        long count = orders.stream().count();
                        long sum = orders.stream().collect(Collectors.summingLong(Order::getTotal));
                        double average = orders.stream().mapToInt(Order::getTotal).average().orElse(0);
                        return new Totals(count, sum, average);
                    }
                    Range byCollections(List<Integer> values) {
                        return new Range(Collections.min(values), Collections.max(values));
                    }
                    int onlyMax(List<Order> orders, List<Order> archive) {
                        int current = orders.stream().mapToInt(Order::getTotal).max().orElse(0);
                        int old = archive.stream().mapToInt(Order::getTotal).min().orElse(0);
                        return Math.max(current, old);
                    }
                    Totals differentFilters(List<Order> orders) {
                        long paid = orders.stream().filter(Order::isPaid).count();
                        long debt = orders.stream().filter(Order::isOverdue).mapToLong(Order::getTotal).sum();
                        return new Totals(paid, debt, 0);
                    }
                    IntSummaryStatistics good(List<Order> orders) {
                        return orders.stream().mapToInt(Order::getTotal).summaryStatistics();
                    }
                }
                """);

        // Разные коллекции и разный отбор одним проходом так просто не собрать
        assertThat(violations).extracting(Violation::line).containsExactly(4, 9, 14);
        assertThat(violations.get(0).message())
                .contains("По 'orders' считают min, max", "Collectors.teeing", "summaryStatistics()");
        assertThat(violations.get(1).message()).contains("count, sum, average");
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.INFO);
    }
}
