package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.OptionalMisuseRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OptionalMisuseRuleTest {
    private final OptionalMisuseRule rule = new OptionalMisuseRule();

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

    @Test
    void optionalsOfStreamFilteredByPresence() {
        // До лямбды после filter(Optional::isPresent) доходят только непустые значения
        assertThat(RuleTests.lines(rule, """
                class Cache {
                    List<Card> present(Map<String, Optional<Card>> cards) {
                        return cards.values().stream()
                                .filter(Optional::isPresent)
                                .map(card -> new Card(card.get()))
                                .toList();
                    }
                    List<Card> notEmpty(List<Optional<Card>> cards) {
                        return cards.stream().filter(card -> !card.isEmpty()).map(card -> card.get().copy()).toList();
                    }
                    List<Card> unchecked(List<Optional<Card>> cards) {
                        return cards.stream().filter(card -> card != null).map(card -> card.get().copy()).toList();
                    }
                }
                """)).containsExactly(12);
    }
}
