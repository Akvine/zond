package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:72 - jr:75: неэффективное использование стримов
 */
class StreamUsageRulesTest {

    @Test
    void streamCountForExistence() {
        assertThat(RuleTests.lines(new CheckStreamCountForExistenceRule(), """
                class Sample {
                    boolean run(List<String> items) {
                        boolean a = items.stream().filter(String::isEmpty).count() > 0;
                        boolean b = items.stream().filter(String::isEmpty).count() == 0;
                        boolean c = 0 < items.stream().filter(String::isEmpty).count();
                        boolean ok1 = items.stream().filter(String::isEmpty).count() > 5;
                        boolean ok2 = items.stream().count() > 0;
                        boolean ok3 = items.stream().anyMatch(String::isEmpty);
                        return a;
                    }
                }
                """)).containsExactly(3, 4, 5);
    }

    @Test
    void findFirstIsPresent() {
        assertThat(RuleTests.lines(new CheckFindFirstIsPresentRule(), """
                class Sample {
                    boolean run(List<String> items) {
                        boolean a = items.stream().filter(String::isEmpty).findFirst().isPresent();
                        boolean b = items.stream().filter(String::isEmpty).findAny().isEmpty();
                        boolean ok1 = items.stream().findFirst().isPresent();
                        boolean ok2 = items.stream().filter(String::isEmpty).map(String::trim).findFirst().isPresent();
                        Optional<String> ok3 = items.stream().filter(String::isEmpty).findFirst();
                        return a;
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void sortedFindFirst() {
        assertThat(RuleTests.lines(new CheckSortedFindFirstRule(), """
                class Sample {
                    void run(List<Integer> items) {
                        Optional<Integer> a = items.stream().sorted().findFirst();
                        Optional<Integer> b = items.stream().sorted(Comparator.reverseOrder()).findFirst();
                        Optional<Integer> ok1 = items.stream().sorted().skip(1).findFirst();
                        Optional<Integer> ok2 = items.stream().min(Comparator.naturalOrder());
                        List<Integer> ok3 = items.stream().sorted().toList();
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void streamInLoop() {
        assertThat(RuleTests.lines(new CheckStreamInLoopRule(), """
                class Sample {
                    void run(List<Order> orders, List<Item> items) {
                        for (Order order : orders) {
                            long count = items.stream().filter(item -> item.belongsTo(order)).count();
                            order.getLines().stream().forEach(this::use);
                            List<String> local = new ArrayList<>();
                            local.stream().count();
                        }
                        orders.forEach(order -> items.stream().filter(item -> item.belongsTo(order)).count());
                        items.stream().count();
                    }
                }
                """)).containsExactly(4, 9);
    }
}
