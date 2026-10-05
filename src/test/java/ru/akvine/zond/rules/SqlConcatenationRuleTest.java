package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.SqlConcatenationRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlConcatenationRuleTest {
    private final SqlConcatenationRule rule = new SqlConcatenationRule();

    @Test
    void findsQueriesBuiltFromRequestData() {
        List<Violation> violations = RuleTests.check(rule, """
                @RestController
                class Users {
                    @GetMapping("/users")
                    void find(@RequestParam String name, @RequestParam int id, @RequestParam String sort) {
                        String q1 = "select * from users where name = '" + name + "'";
                        String filter = "name like '%" + name + "%'";
                        String q2 = "select * from users where " + filter;
                        String ok1 = "delete from orders where id = " + id;
                        String ok2 = "select * from " + TABLE_NAME + " where id = ?";
                        String ok3 = "Failed to select item from list " + name;
                        String order = "desc".equals(sort) ? "DESC" : "ASC";
                        String ok4 = "select * from users order by name " + order;
                    }
                }
                """);

        // Число запрос не испортит; порядок сортировки выбирается из двух литералов
        assertThat(violations).extracting(Violation::line).containsExactly(5, 7);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:18"));
        assertThat(violations.get(0).message()).contains("name (данные запроса: name)");
    }

    @Test
    void ignoresValuesTheClientDoesNotControl() {
        assertThat(RuleTests.lines(rule, """
                @Service
                class Reports {
                    @Value("${reports.table}")
                    private String table;

                    // Значение из настроек: менять их может только тот, у кого и так есть доступ к серверу
                    void fromSettings() {
                        String sql = "select * from " + table + " where id = ?";
                    }

                    // Параметр внутреннего метода: данных клиента в него не приходит
                    void fromCode(String column, List<Long> ids) {
                        String sql = "select " + column + " from reports where id in (" + placeholders(ids) + ")";
                        String text = "UPDATE " + tableOf(column) + " SET active = 0";
                    }
                }
                """)).isEmpty();
    }
}
