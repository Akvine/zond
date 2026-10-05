package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.logical.CastWithoutInstanceofRule;
import ru.akvine.zond.rules.logical.DivisionBySizeRule;
import ru.akvine.zond.rules.logical.IndexWithoutLengthCheckRule;
import ru.akvine.zond.rules.logical.NullUnboxingRule;
import ru.akvine.zond.rules.logical.NullableDereferenceRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:220 - jr:224: значение используется без проверки, которая стояла бы на пути к нему
 */
class FlowCheckRulesTest {

    @Test
    void nullableDereference() {
        assertThat(RuleTests.lines(new NullableDereferenceRule(), """
                class Sample {
                    void run(Map<String, Handler> handlers, String key, Optional<User> user) {
                        Handler handler = handlers.get(key);
                        handler.handle();
                        Handler checked = handlers.get(key);
                        if (checked != null) {
                            checked.handle();
                        }
                        User found = user.orElse(null);
                        if (found == null) {
                            return;
                        }
                        found.getName();
                        for (String name : handlers.keySet()) {
                            Handler each = handlers.get(name);
                            each.handle();
                        }
                        Handler required = handlers.get(key);
                        Objects.requireNonNull(required);
                        required.handle();
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void nullUnboxing() {
        assertThat(RuleTests.lines(new NullUnboxingRule(), """
                class Sample {
                    void run(Boolean active, boolean plain, Boolean checked) {
                        if (active) {}
                        if (plain) {}
                        if (checked != null && checked) {}
                        if (Boolean.TRUE.equals(active)) {}
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void castWithoutInstanceof() {
        assertThat(RuleTests.lines(new CastWithoutInstanceofRule(), """
                class Sample {
                    void run(Object value, Object other) {
                        String text = (String) value;
                        if (other instanceof Integer) {
                            Integer number = (Integer) other;
                        }
                    }
                    public boolean equals(Object o) {
                        if (o == null || getClass() != o.getClass()) return false;
                        Sample that = (Sample) o;
                        return true;
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void indexWithoutLengthCheck() {
        assertThat(RuleTests.lines(new IndexWithoutLengthCheckRule(), """
                class Sample {
                    void run(String text, String line) {
                        text.charAt(0);
                        if (!text.isEmpty()) {
                            text.charAt(0);
                        }
                        String[] parts = line.split(",");
                        use(parts[0]);
                        use(parts[1]);
                        if (parts.length > 2) {
                            use(parts[2]);
                        }
                    }
                }
                """)).containsExactly(3, 9);
    }

    @Test
    void divisionBySize() {
        assertThat(RuleTests.lines(new DivisionBySizeRule(), """
                class Sample {
                    double average(List<Integer> items, int total) {
                        return total / items.size();
                    }
                    double safe(List<Integer> items, int total) {
                        if (items.isEmpty()) {
                            return 0;
                        }
                        return total / items.size();
                    }
                    int viaVariable(List<Integer> items, int total) {
                        int count = items.size();
                        return count == 0 ? 0 : total / count;
                    }
                }
                """)).containsExactly(3);
    }
}
