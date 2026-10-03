package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckNamingConventionRuleTest {
    private final CheckNamingConventionRule rule = new CheckNamingConventionRule();

    @Test
    void findsNamesThatBreakConventions() {
        List<Violation> violations = RuleTests.check(rule, """
                package Sample.Pkg;

                class bad_class {
                    private static final int maxSize = 1;
                    private static final int MAX_SIZE = 1;
                    private static final Logger log = null;
                    private int Bad_field;
                    private int goodField;
                    void Bad_Method(int Bad_param, int goodParam) {
                        int Bad_local = 0;
                        int goodLocal = 0;
                    }
                    void goodMethod() {
                    }
                }
                enum Color {
                    RED, darkBlue
                }
                class GoodClass {
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(1, 3, 4, 7, 9, 9, 10, 17);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:42"));
        assertThat(violations.get(0).message()).contains("пакета");
        assertThat(violations.get(1).message()).contains("'bad_class'", "UpperCamelCase");
        assertThat(violations.get(7).message()).contains("'darkBlue'");
    }

    @Test
    void allowsUnderscoresInTestMethodNames() {
        assertThat(RuleTests.lines(rule, """
                class SampleTest {
                    @Test
                    void returns_empty_when_missing() {}
                }
                """)).isEmpty();
    }
}
