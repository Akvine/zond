package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.ReferenceEqualityRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceEqualityRuleTest {
    private final ReferenceEqualityRule rule = new ReferenceEqualityRule();

    @Test
    void findsStringsAndWrappersComparedByReference() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    private Integer cached;
                    boolean check(String name, String other, Integer a, Long b, int primitive, Object any) {
                        boolean r1 = name == "admin";
                        boolean r2 = name != other;
                        boolean r3 = a == this.cached;
                        boolean r4 = any.toString() == name;
                        boolean ok1 = name == null;
                        boolean ok2 = a == primitive;
                        boolean ok3 = primitive == 5;
                        boolean ok4 = name.equals(other);
                        boolean ok5 = any == this;
                        return r1;
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(4, 5, 6, 7);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:14"));
        assertThat(violations.get(0).message()).contains("строк");
        assertThat(violations.get(2).message()).contains("Integer");
    }
}
