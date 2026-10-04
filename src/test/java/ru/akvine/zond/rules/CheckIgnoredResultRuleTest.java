package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.logical.CheckIgnoredResultRule;

import static org.assertj.core.api.Assertions.assertThat;

class CheckIgnoredResultRuleTest {
    private final CheckIgnoredResultRule rule = new CheckIgnoredResultRule();

    @Test
    void findsIgnoredResultsOfImmutableObjects() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run(String text, BigDecimal amount, LocalDate date, List<String> list, StringBuilder builder) {
                        text.replace("a", "b");
                        text.trim();
                        amount.add(BigDecimal.ONE);
                        date.plusDays(1);
                        "literal".toUpperCase();
                        text = text.trim();
                        String upper = text.toUpperCase();
                        list.add(text);
                        builder.append(text);
                        amount.intValueExact();
                        print(text.trim());
                        list.removeIf(item -> text.equals(item));
                        String chosen = switch (list.size()) {
                            case 0 -> text.trim();
                            default -> text.strip();
                        };
                    }
                }
                """)).containsExactly(3, 4, 5, 6, 7);
    }
}
