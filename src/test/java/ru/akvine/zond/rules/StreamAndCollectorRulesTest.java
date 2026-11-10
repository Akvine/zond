package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.NullInCollectorRule;
import ru.akvine.zond.rules.logical.OptionalMisuseRule;
import ru.akvine.zond.rules.logical.ReducerAsComparatorRule;
import ru.akvine.zond.rules.streams.StreamWithoutTerminalRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:484 - jr:486 и дополнение к jr:24: стрим, который не выполняется, и итоги, которых может не быть
 */
class StreamAndCollectorRulesTest {

    @Test
    void streamWithoutTerminalOperationDoesNothing() {
        StreamWithoutTerminalRule rule = new StreamWithoutTerminalRule();
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    void run(List<Order> orders, Stream<Order> stream, Optional<Order> first) {
                        orders.stream().map(order -> order.markChecked());
                        orders.stream()
                                .filter(Order::isPaid)
                                .peek(order -> audit.record(order));
                        stream.sorted();
                        orders.forEach(order -> order.getItems().stream().map(Item::recalculate));
                        orders.stream().map(Order::getId).forEach(ids::add);
                        List<Long> ids = orders.stream().map(Order::getId).toList();
                        first.map(Order::getId);
                        query.limit(10);
                        return;
                    }
                    Stream<Item> items(List<Order> orders) {
                        return orders.stream().flatMap(order -> order.getItems().stream().filter(Item::isActive));
                    }
                }
                """);

        // Optional.map и limit() у построителя запроса - не стрим; лямбда в flatMap свой стрим возвращает наружу
        assertThat(violations).extracting(Violation::line).containsExactly(3, 6, 7, 8);
        assertThat(violations.get(0).message()).contains("кончается на 'map(...)'", "ленивы", "forEach, collect");
        assertThat(rule.confidence()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void selectorInPlaceOfComparatorGivesWrongResult() {
        ReducerAsComparatorRule rule = new ReducerAsComparatorRule();
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    int run(List<Integer> values) {
                        int max = values.stream().max(Integer::max).orElse(0);
                        int min = values.stream().min(Math::min).orElse(0);
                        values.sort((a, b) -> Math.max(a, b));
                        List<Integer> sorted = values.stream().sorted((a, b) -> a > b ? a : b).toList();
                        int top = Collections.max(values, Integer::max);
                        int right = values.stream().max(Integer::compare).orElse(0);
                        int reduced = values.stream().reduce(Integer::max).orElse(0);
                        int natural = values.stream().max(Comparator.naturalOrder()).orElse(0);
                        values.sort((a, b) -> a > b ? 1 : -1);
                        return Math.max(max, min);
                    }
                }
                """);

        // Integer::compare и reduce(Integer::max) - верные способы; Math.max(a, b) сам по себе - обычный вызов
        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5, 6, 7);
        assertThat(violations.get(0).message())
                .contains("'max(Integer::max)'", "нужен компаратор", "Integer::compare", "mapToInt(...).max()");
        assertThat(violations.get(2).message()).contains("лямбда '(a, b) -> Math.max(a, b)'");
        assertThat(rule.confidence()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void collectorsRejectNull() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Order.java", """
                class Order {
                    private Long id;
                    @Nullable
                    private String comment;
                    private String status;
                    Long getId() {
                        return id;
                    }
                    String getComment() {
                        return comment;
                    }
                    @Nullable
                    String getManager() {
                        return null;
                    }
                }
                """);
        files.put("Report.java", """
                class Report {
                    void build(List<Order> orders, Map<Long, String> titles) {
                        Map<Long, String> comments = orders.stream().collect(Collectors.toMap(Order::getId, Order::getComment));
                        Map<Long, String> byTitle = orders.stream()
                                .collect(Collectors.toMap(Order::getId, order -> titles.get(order.getId())));
                        Map<Long, Order> latest = orders.stream()
                                .collect(Collectors.toMap(Order::getId, order -> find(order).orElse(null)));
                        Map<String, List<Order>> byManager = orders.stream().collect(Collectors.groupingBy(Order::getManager));
                        Map<String, List<Order>> byComment = orders.stream()
                                .collect(Collectors.groupingBy(order -> order.isDraft() ? null : order.getStatus()));
                        Map<Long, String> statuses = orders.stream().collect(Collectors.toMap(Order::getId, Order::getStatus));
                        Map<Long, String> safe = orders.stream()
                                .collect(Collectors.toMap(Order::getId, order -> titles.getOrDefault(order.getId(), "")));
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new NullInCollectorRule(), files);

        // Поле без @Nullable и getOrDefault со значением по умолчанию - не находки
        assertThat(violations).extracting(Violation::line).containsExactly(3, 5, 7, 8, 10);
        assertThat(violations.get(0).message())
                .contains("Значение для Collectors.toMap(...)", "'Order::getComment' помечен @Nullable", "NullPointerException");
        assertThat(violations.get(1).message()).contains("'titles.get(order.getId())' для неизвестного ключа");
        assertThat(violations.get(2).message()).contains("orElse(null)");
        assertThat(violations.get(3).message()).contains("Ключ для Collectors.groupingBy(...)", "null key");
        assertThat(violations.get(4).message()).contains("функция возвращает null");
    }

    @Test
    void primitiveAggregateOfEmptyStreamHasNoValue() {
        List<Violation> violations = RuleTests.check(new OptionalMisuseRule(), """
                class Sample {
                    int run(List<Order> orders) {
                        int max = orders.stream().mapToInt(Order::getTotal).max().getAsInt();
                        double average = orders.stream().mapToInt(Order::getTotal).average().getAsDouble();
                        long first = orders.stream().filter(Order::isPaid).mapToLong(Order::getId).findFirst().getAsLong();
                        int sum = orders.stream().mapToInt(Order::getTotal).reduce(0, Integer::sum);
                        int safe = orders.stream().mapToInt(Order::getTotal).max().orElse(0);
                        if (!orders.isEmpty()) {
                            return orders.stream().mapToInt(Order::getTotal).min().getAsInt();
                        }
                        try {
                            return orders.stream().mapToInt(Order::getTotal).min().getAsInt();
                        } catch (NoSuchElementException exception) {
                            return 0;
                        }
                    }
                }
                """);

        // Непустая коллекция, orElse и перехваченное исключение - не находки
        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5);
        assertThat(violations.get(0).message()).contains("'getAsInt()' без проверки сразу после 'max()'", "orElse(...)");
        assertThat(violations.get(1).message()).contains("'getAsDouble()'", "'average()'");
    }
}
