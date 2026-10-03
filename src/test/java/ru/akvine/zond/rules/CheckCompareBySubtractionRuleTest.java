package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckCompareBySubtractionRuleTest {
    private final CheckCompareBySubtractionRule rule = new CheckCompareBySubtractionRule();

    @Test
    void findsComparisonImplementedBySubtraction() {
        assertThat(RuleTests.lines(rule, """
                class Sample implements Comparable<Sample> {
                    private int age;
                    public int compareTo(Sample other) {
                        return this.age - other.age;
                    }
                    void sort(List<Sample> items) {
                        items.sort((a, b) -> a.age - b.age);
                        Comparator<Sample> byAge = (a, b) -> {
                            return (int) (a.age - b.age);
                        };
                        items.sort((a, b) -> Integer.compare(a.age, b.age));
                        BinaryOperator<Integer> diff = (a, b) -> a - b;
                        items.sort(Comparator.comparingInt(item -> item.age));
                    }
                }
                class Other implements Comparable<Other> {
                    public int compareTo(Other other) {
                        return Integer.compare(1, 2);
                    }
                }
                """)).containsExactly(4, 7, 9);
    }
}
