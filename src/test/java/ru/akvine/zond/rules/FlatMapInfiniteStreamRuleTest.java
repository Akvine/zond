package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.streams.FlatMapInfiniteStreamRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlatMapInfiniteStreamRuleTest {
    private final FlatMapInfiniteStreamRule rule = new FlatMapInfiniteStreamRule();

    @Test
    void findsFlatMapReturningInfiniteStream() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    void run(List<Integer> items) {
                        items.stream().flatMap(item -> Stream.generate(() -> item)).toList();
                        items.stream().flatMap(item -> Stream.iterate(item, i -> i + 1).map(i -> i * 2)).forEach(this::use);
                        Stream<Integer> infinite = Stream.iterate(1, i -> i + 1);
                        items.stream().flatMap(item -> infinite).count();
                        items.stream().flatMap(item -> Stream.generate(() -> item).limit(3)).toList();
                        items.stream().flatMap(item -> Stream.iterate(item, i -> i < 10, i -> i + 1)).toList();
                        items.stream().flatMap(item -> Stream.generate(() -> item)).limit(5).toList();
                        items.stream().flatMap(item -> Stream.of(item, item)).toList();
                        items.stream().flatMap(List::stream).toList();
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 6);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:53"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.CRITICAL);
        assertThat(violations.get(2).message()).contains("'infinite'");
    }
}
