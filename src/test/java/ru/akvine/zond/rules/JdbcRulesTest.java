package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.logical.CheckDeleteWithoutWhereRule;
import ru.akvine.zond.rules.logical.CheckUpdateWithoutWhereRule;
import ru.akvine.zond.rules.performance.CheckMissingBatchProcessingRule;
import ru.akvine.zond.rules.resources.CheckManualResourceCloseRule;
import ru.akvine.zond.rules.security.CheckStatementInsteadOfPreparedRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:87 и jr:89 - jr:92: JDBC, SQL и закрытие ресурсов
 */
class JdbcRulesTest {
    private static final String QUERIES = """
            class Sample {
                @Query("delete from Order o")
                void clear();
                void run() {
                    jdbcTemplate.update("DELETE FROM orders");
                    jdbcTemplate.update("update orders set status = 'NEW'");
                    jdbcTemplate.update("delete from orders where id = ?", id);
                    jdbcTemplate.update("update orders " + "set status = ? " + "where id = ?", status, id);
                    jdbcTemplate.update("delete from orders" + condition);
                    log.info("delete from list failed");
                    String sql = "update orders " + "set status = 'DONE'";
                }
            }
            """;

    @Test
    void statementInsteadOfPrepared() {
        assertThat(RuleTests.lines(new CheckStatementInsteadOfPreparedRule(), """
                @RestController
                class Sample {
                    @GetMapping("/run")
                    void run(@RequestParam String name, PreparedStatement prepared) throws SQLException {
                        Statement statement = connection.createStatement();
                        statement.executeQuery("select * from users where name = '" + name + "'");
                        String sql = "delete from users where name = '" + name + "'";
                        statement.execute(sql);
                        connection.createStatement().executeUpdate(sql);
                        statement.executeQuery("select * from users");
                        statement.executeQuery(SELECT_ALL);
                        statement.executeQuery(configuredQuery);
                        prepared.executeQuery();
                    }
                    // Запрос приходит параметром, а кто его передает, неизвестно: данных клиента в нем не видно
                    void internal(Statement statement, String sql) throws SQLException {
                        statement.execute(sql);
                    }
                }
                // Обертка над Statement передает запрос дальше как есть
                class StatementProxy {
                    private Statement target;
                    public boolean execute(String sql) throws SQLException {
                        return target.execute(sql);
                    }
                }
                """)).containsExactly(6, 8, 9);
    }

    @Test
    void deleteWithoutWhere() {
        assertThat(RuleTests.lines(new CheckDeleteWithoutWhereRule(), QUERIES)).containsExactly(2, 5);
    }

    @Test
    void updateWithoutWhere() {
        assertThat(RuleTests.lines(new CheckUpdateWithoutWhereRule(), QUERIES)).containsExactly(6, 11);
    }

    @Test
    void missingBatchProcessing() {
        assertThat(RuleTests.lines(new CheckMissingBatchProcessingRule(), """
                class Sample {
                    void run(List<Order> orders, PreparedStatement statement) throws SQLException {
                        for (Order order : orders) {
                            jdbcTemplate.update("insert into orders values (?)", order.getId());
                            statement.executeUpdate();
                            entityManager.persist(order);
                            orderRepository.save(order);
                            statement.addBatch();
                        }
                        jdbcTemplate.update("insert into audit values (?)", 1);
                    }
                }
                """)).containsExactly(4, 5, 6);
    }

    @Test
    void manualResourceClose() {
        assertThat(RuleTests.lines(new CheckManualResourceCloseRule(), """
                class Sample {
                    void run() throws IOException {
                        FileInputStream in = new FileInputStream("a.txt");
                        in.read();
                        in.close();
                        FileInputStream safe = new FileInputStream("b.txt");
                        try {
                            safe.read();
                        } finally {
                            safe.close();
                        }
                        try (FileInputStream scoped = new FileInputStream("c.txt")) {
                            scoped.read();
                        }
                        FileInputStream leaked = new FileInputStream("d.txt");
                        leaked.read();
                    }
                }
                """)).containsExactly(3);
    }
}
