package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckSqlConcatenationRuleTest {
    private final CheckSqlConcatenationRule rule = new CheckSqlConcatenationRule();

    @Test
    void findsQueriesBuiltWithParameters() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    void run(String name, int id) {
                        String q1 = "select * from users where name = '" + name + "'";
                        String q2 = "UPDATE " + table + " SET active = 0 WHERE id = " + id;
                        String q3 = "delete from orders where id = " + (id + 1);
                        String ok1 = "select * from " + TABLE_NAME + " where id = ?";
                        String ok2 = "select * from users " + "where id = ?";
                        String ok3 = "Failed to select item from list " + name;
                        String ok4 = "name: " + name;
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:18"));
        assertThat(violations.get(1).message()).contains("table, id");
    }
}
