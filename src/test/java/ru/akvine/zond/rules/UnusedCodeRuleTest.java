package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.UnusedCodeRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnusedCodeRuleTest {
    private final UnusedCodeRule rule = new UnusedCodeRule();

    @Test
    void findsUnusedImportsFieldsMethodsAndParameters() {
        List<Violation> violations = RuleTests.check(rule, """
                package sample;

                import java.util.List;
                import java.util.Map;
                import java.util.Set;
                import java.util.function.Function;
                import org.springframework.stereotype.Service;
                import static java.util.Objects.requireNonNull;

                /**
                 * Uses {@link Set} in documentation.
                 */
                @Service
                class Sample {
                    private static final long serialVersionUID = 1L;
                    private int unusedField;
                    private int usedField;
                    @Autowired
                    private Object injected;

                    List<String> run(Function<String, String> mapper) {
                        usedField++;
                        helper(1, 2);
                        return List.of(mapper.apply("x"));
                    }

                    private void helper(int used, int unusedParameter) {
                        print(used);
                    }

                    private void unusedMethod() {
                    }

                    @PostConstruct
                    private void init() {
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(4, 8, 16, 27, 31);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:38"));
        assertThat(violations.get(0).message()).contains("импорт", "java.util.Map");
        assertThat(violations.get(2).message()).contains("поле", "'unusedField'");
        assertThat(violations.get(3).message()).contains("параметр", "'unusedParameter'");
        assertThat(violations.get(4).message()).contains("метод", "'unusedMethod'");
    }

    @Test
    void countsMethodReferenceAsUsage() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private static final Set<String> NAMES = Set.of("a");

                    boolean run(List<String> items) {
                        return matches(items, "a");
                    }

                    private boolean matches(List<String> items, String expected) {
                        return items.stream().anyMatch(NAMES::contains) && items.stream().anyMatch(expected::equals);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void ignoresFieldsOfLombokClasses() {
        assertThat(RuleTests.lines(rule, """
                @Getter
                class Sample {
                    private int value;
                }
                """)).isEmpty();
    }
}
