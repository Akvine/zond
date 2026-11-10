package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.ArraysAsListPrimitiveRule;
import ru.akvine.zond.rules.logical.IgnoredResultRule;
import ru.akvine.zond.rules.logical.NarrowingCastRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дополнения к jr:25, jr:134 и jr:286: результат BigDecimal отброшен в лямбде, массив примитивов в List.of()
 * и приведение long к int, скрытое в составном присваивании
 */
class ValuePitfallRulesTest {

    @Test
    void immutableResultIsLostInConsumerLambda() {
        List<Violation> violations = RuleTests.check(new IgnoredResultRule(), """
                class Sample {
                    BigDecimal total(List<Item> items, Optional<Item> extra) {
                        BigDecimal total = BigDecimal.ZERO;
                        BigInteger count = BigInteger.ZERO;
                        items.forEach(item -> total.add(item.getPrice()));
                        extra.ifPresent(item -> count.add(BigInteger.ONE));
                        total.setScale(2, RoundingMode.HALF_UP);
                        List<BigDecimal> doubled = items.stream().map(item -> total.add(item.getPrice())).toList();
                        boolean any = items.stream().anyMatch(item -> total.equals(item.getPrice()));
                        BigDecimal sum = total.add(BigDecimal.ONE);
                        return sum;
                    }
                }
                """);

        // В map и anyMatch результат лямбды используют, в forEach и ifPresent - отбрасывают
        assertThat(violations).extracting(Violation::line).containsExactly(5, 6, 7);
        assertThat(violations.get(0).message()).contains("total.add(item.getPrice())", "BigDecimal неизменяем");
        assertThat(violations.get(1).message()).contains("BigInteger неизменяем");
    }

    @Test
    void primitiveArrayBecomesSingleElement() {
        List<Violation> violations = RuleTests.check(new ArraysAsListPrimitiveRule(), """
                class Sample {
                    void run(int[] numbers, Integer[] boxed, String[] names) {
                        List<int[]> a = Arrays.asList(numbers);
                        List<int[]> b = List.of(numbers);
                        Set<int[]> c = Set.of(numbers);
                        Stream<int[]> d = Stream.of(numbers);
                        List<long[]> e = List.of(new long[] {1L, 2L});
                        List<Integer> ok1 = Arrays.asList(boxed);
                        List<String> ok2 = List.of(names);
                        List<Integer> ok3 = List.of(1, 2, 3);
                        List<Integer> ok4 = Arrays.stream(numbers).boxed().toList();
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5, 6, 7);
        assertThat(violations.get(0).message()).contains("получится список из одного элемента");
        assertThat(violations.get(1).message()).contains("'List.of(numbers)'", "Arrays.stream(...).boxed().toList()");
        assertThat(violations.get(3).message()).contains("получится стрим из одного элемента");
    }

    @Test
    void compoundAssignmentHidesNarrowing() {
        List<Violation> violations = RuleTests.check(new NarrowingCastRule(), """
                class Sample {
                    int total(List<Order> orders, long bonus) {
                        int total = 0;
                        long wide = 0;
                        for (Order order : orders) {
                            long amount = order.getAmount();
                            total += amount;
                            wide += amount;
                            total += 1;
                        }
                        total -= bonus;
                        int explicit = (int) bonus;
                        return total;
                    }
                }
                """);

        // total += amount компилируется как total = (int) (total + amount): приведение в коде не видно
        assertThat(violations).extracting(Violation::line).containsExactly(7, 11, 12);
        assertThat(violations.get(0).message()).contains("'total += amount'", "неявно приводится к int", "Math.addExact");
        assertThat(violations.get(2).message()).contains("Math.toIntExact");
    }
}
