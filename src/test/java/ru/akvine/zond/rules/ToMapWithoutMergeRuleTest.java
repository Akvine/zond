package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.streams.ToMapWithoutMergeRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToMapWithoutMergeRuleTest {
    private final ToMapWithoutMergeRule rule = new ToMapWithoutMergeRule();

    @Test
    void findsToMapWithoutMergeFunction() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    void run(List<User> users) {
                        Map<String, User> a = users.stream().collect(Collectors.toMap(User::getName, user -> user));
                        Map<String, User> b = users.stream().collect(toMap(User::getName, Function.identity()));
                        Map<String, User> c = users.stream().collect(Collectors.toConcurrentMap(User::getName, user -> user));
                        Map<String, User> ok1 = users.stream().collect(Collectors.toMap(User::getName, user -> user, (left, right) -> left));
                        Map<String, User> ok2 = users.stream().collect(Collectors.toMap(User::getName, user -> user, (left, right) -> left, TreeMap::new));
                        Map<String, List<User>> ok3 = users.stream().collect(Collectors.groupingBy(User::getName));
                        mapper.toMap(first, second);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:49"));
        assertThat(violations).allMatch(violation -> violation.errorType() == ErrorType.STREAM);
    }
}
