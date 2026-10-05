package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.logical.CollectionModificationInForEachRule;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionModificationInForEachRuleTest {
    private final CollectionModificationInForEachRule rule = new CollectionModificationInForEachRule();

    @Test
    void findsModificationOfIteratedCollection() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run(List<String> items, Map<String, String> map) {
                        for (String item : items) {
                            items.remove(item);
                        }
                        for (String key : map.keySet()) {
                            map.remove(key);
                        }
                        items.forEach(item -> items.add(item));
                        for (String item : items) {
                            if (item.isEmpty()) {
                                items.remove(item);
                                break;
                            }
                            other.add(item);
                        }
                        for (String item : new ArrayList<>(items)) {
                            items.remove(item);
                        }
                    }
                }
                """)).containsExactly(4, 7, 9);
    }
}
