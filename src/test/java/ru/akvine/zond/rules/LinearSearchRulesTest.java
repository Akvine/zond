package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.LinearSearchInsteadOfMapRule;
import ru.akvine.zond.rules.performance.StreamInLoopRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило jr:362: поиск элемента по ключу перебором там, где быстрый доступ дала бы Map
 */
class LinearSearchRulesTest {
    private static final String LOOKUPS_IN_LOOP = """
            class Report {
                List<Row> build(List<Order> orders, List<Customer> customers, List<Item> items) {
                    List<Row> rows = new ArrayList<>();
                    for (Order order : orders) {
                        Customer customer = customers.stream()
                                .filter(candidate -> candidate.getId().equals(order.getCustomerId()))
                                .findFirst()
                                .orElse(null);
                        List<Item> own = items.stream()
                                .filter(item -> Objects.equals(item.getOrder().getId(), order.getId()) && item.isActive())
                                .toList();
                        boolean vip = customers.stream().anyMatch(candidate -> candidate.getId() == order.getVipId());
                        long active = customers.stream().filter(Customer::isActive).count();
                        boolean fixed = customers.stream().anyMatch(candidate -> candidate.getCode().equals("ROOT"));
                        List<Line> lines = order.getLines().stream()
                                .filter(line -> line.getSku().equals(order.getMainSku()))
                                .toList();
                        rows.add(new Row(order, customer, own, vip, active, fixed, lines));
                    }
                    return rows;
                }
            }
            """;

    @Test
    void lookupByKeyInsideLoopNeedsMap() {
        List<Violation> violations = RuleTests.check(new LinearSearchInsteadOfMapRule(), LOOKUPS_IN_LOOP);

        // Стрим без равенства по ключу, поиск постоянного значения и поиск в коллекции самого элемента - не сюда
        assertThat(violations).extracting(Violation::line).containsExactly(5, 9, 12);
        assertThat(violations).extracting(Violation::confidence).containsOnlyNulls();
        assertThat(violations.get(0).message())
                .contains("'customers' по 'candidate.getId()'")
                .contains("get(order.getCustomerId())");
        assertThat(violations.get(1).message()).contains("'items' по 'item.getOrder().getId()'");
    }

    @Test
    void streamInLoopLeavesKeyLookupsToMapRule() {
        // Обычный стрим по внешней коллекции остается за прежним правилом, поиск по ключу оно больше не дублирует
        assertThat(RuleTests.lines(new StreamInLoopRule(), LOOKUPS_IN_LOOP)).containsExactly(13, 14);
    }

    @Test
    void nestedLoopWithEqualityIsLookup() {
        assertThat(RuleTests.lines(new LinearSearchInsteadOfMapRule(), """
                class Report {
                    private static final List<String> KINDS = List.of("A", "B", "C");
                    void build(List<Order> orders, List<Customer> customers) {
                        for (Order order : orders) {
                            for (Customer customer : customers) {
                                if (customer.getId().equals(order.getCustomerId())) {
                                    order.setCustomerName(customer.getName());
                                    break;
                                }
                            }
                            for (Customer customer : customers) {
                                if (customer.isActive()) {
                                    order.touch();
                                }
                            }
                            for (Customer customer : customers) {
                                if (customer.getId().equals(order.getCustomerId())) {
                                    order.setCustomerName(customer.getName());
                                } else {
                                    order.skip(customer);
                                }
                            }
                            for (Line line : order.getLines()) {
                                if (line.getSku().equals(order.getMainSku())) {
                                    order.setMain(line);
                                }
                            }
                            for (String kind : KINDS) {
                                if (kind.length() == order.getKindLength()) {
                                    order.setKind(kind);
                                }
                            }
                        }
                        orders.forEach(order -> {
                            for (Customer customer : customers) {
                                if (Objects.equals(customer.getCode(), order.getCustomerCode())) {
                                    order.setCustomer(customer);
                                }
                            }
                        });
                    }
                }
                """)).containsExactly(5, 35);
    }

    @Test
    void shortOuterLoopIsNotQuadratic() {
        // Значений перечисления и перечисленных в коде режимов считаные единицы
        assertThat(RuleTests.lines(new LinearSearchInsteadOfMapRule(), """
                class Report {
                    private static final List<String> MODES = List.of("fast", "slow");
                    void build(List<Rule> rules, Map<String, Order> orders) {
                        for (Level level : Level.values()) {
                            List<Rule> ofLevel = rules.stream().filter(rule -> rule.level() == level).toList();
                        }
                        for (String mode : MODES) {
                            boolean used = rules.stream().anyMatch(rule -> rule.mode().equals(mode));
                        }
                        for (Order order : orders.values()) {
                            boolean known = rules.stream().anyMatch(rule -> rule.code().equals(order.getRuleCode()));
                        }
                    }
                }
                """)).containsExactly(11);
    }

    @Test
    void lookupMethodOverFieldIsSuspicion() {
        List<Violation> violations = RuleTests.check(new LinearSearchInsteadOfMapRule(), """
                class Registry {
                    private final List<Handler> handlers;
                    private final List<String> modes = List.of("fast", "slow");
                    Handler byType(String type) {
                        return handlers.stream()
                                .filter(handler -> handler.getType().equals(type))
                                .findFirst()
                                .orElseThrow();
                    }
                    Handler byCode(int code) {
                        for (Handler handler : handlers) {
                            if (handler.getCode() == code) {
                                return handler;
                            }
                        }
                        return null;
                    }
                    boolean has(String type) {
                        return handlers.stream().anyMatch(handler -> handler.getType().equals(type));
                    }
                    List<Handler> allOf(String type) {
                        return handlers.stream().filter(handler -> handler.getType().equals(type)).toList();
                    }
                    Handler supporting(Request request) {
                        return handlers.stream().filter(handler -> handler.supports(request)).findFirst().orElseThrow();
                    }
                    Handler main() {
                        return handlers.stream().filter(handler -> handler.getType().equals("main")).findFirst().orElseThrow();
                    }
                    Handler local(List<Handler> given, String type) {
                        return given.stream().filter(handler -> handler.getType().equals(type)).findFirst().orElseThrow();
                    }
                }
                """);

        // Отбор всех подходящих, произвольное условие, постоянный ключ и поиск в параметре - не метод поиска по ключу
        assertThat(violations).extracting(Violation::line).containsExactly(5, 11, 19);
        assertThat(violations).extracting(Violation::confidence).containsOnly(Confidence.SUSPICION);
        assertThat(violations.get(0).message()).contains("'handlers' по 'handler.getType()'").contains("храните рядом Map");
    }

    @Test
    void testsAreNotChecked() {
        assertThat(RuleTests.lines(new LinearSearchInsteadOfMapRule(), """
                class ReportTest {
                    @Test
                    void builds() {
                        for (Order order : orders) {
                            Customer customer = customers.stream()
                                    .filter(candidate -> candidate.getId().equals(order.getCustomerId()))
                                    .findFirst()
                                    .orElseThrow();
                        }
                    }
                }
                """)).isEmpty();
    }
}
