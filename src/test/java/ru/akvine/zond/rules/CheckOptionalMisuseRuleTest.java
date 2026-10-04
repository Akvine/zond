package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.CheckOptionalMisuseRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckOptionalMisuseRuleTest {
    private final CheckOptionalMisuseRule rule = new CheckOptionalMisuseRule();

    @Test
    void findsNullReturnAndUncheckedGet() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    Optional<User> find(Long id) {
                        if (id == null) {
                            return null;
                        }
                        return repository.findById(id);
                    }
                    User load(Long id, Optional<User> cached) {
                        User first = users.stream().findFirst().get();
                        User second = repository.findById(id).get();
                        return cached.get();
                    }
                    User safe(Optional<User> cached, Map<String, User> map, Supplier<User> supplier) {
                        if (cached.isPresent()) {
                            return cached.get();
                        }
                        User fromMap = map.get("key");
                        return supplier.get();
                    }
                    User filtered(Optional<User> cached) {
                        return cached.filter(User::isActive).isPresent() ? cached.get() : fallback;
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(4, 9, 10, 11);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:24"));
        assertThat(violations.get(0).message()).contains("'find'", "null");
    }
}
