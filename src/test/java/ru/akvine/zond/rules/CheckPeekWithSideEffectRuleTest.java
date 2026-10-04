package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.streams.CheckPeekWithSideEffectRule;

import static org.assertj.core.api.Assertions.assertThat;

class CheckPeekWithSideEffectRuleTest {
    private final CheckPeekWithSideEffectRule rule = new CheckPeekWithSideEffectRule();

    @Test
    void findsPeekThatChangesState() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run(List<User> users, List<User> seen) {
                        users.stream().peek(user -> seen.add(user)).toList();
                        users.stream().peek(seen::add).toList();
                        users.stream().peek(user -> user.setActive(true)).toList();
                        users.stream().peek(user -> counter++).toList();
                        users.stream().peek(user -> { total = total + 1; }).toList();
                        users.stream().peek(user -> log.debug("user {}", user)).toList();
                        users.stream().peek(System.out::println).toList();
                        iterator.peek();
                    }
                }
                """)).containsExactly(3, 4, 5, 6, 7);
    }
}
