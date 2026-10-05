package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.SqlConcatenationRule;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Данные извне прослеживаются через конструкторы, записи (record), лямбды и мапперы; значение enum
 * и значение, проверенное по смыслу условия, опасным не считается.
 * Строка, на которой ожидается находка, помечена комментарием // @SQL
 */
class TaintPrecisionTest {
    private static final String CODE = """
            @RestController
            class Orders {
                @PostMapping("/orders")
                void create(@RequestParam String comment, @RequestParam Status status,
                            @RequestParam List<String> tags, @RequestParam String role) {
                    OrderRecord record = new OrderRecord(comment, 1);
                    jdbc.update("insert into orders (comment) values ('" + record.comment() + "')"); // @SQL
                    jdbc.update("insert into orders (status) values ('" + status + "')");
                    tags.forEach(tag -> jdbc.update("insert into tags values ('" + tag + "')")); // @SQL
                    if ("admin".equals(role)) {
                        return;
                    }
                    jdbc.update("update users set role = '" + role + "'"); // @SQL
                }

                @PostMapping("/saved")
                void saved(@RequestParam String comment) {
                    service.store(new Order(comment));
                }

                @GetMapping("/sorted")
                void sorted(@RequestParam String sort, @RequestParam String column, @RequestParam String order) {
                    requireAllowed(sort);
                    jdbc.query("select * from items order by " + sort, mapper);
                    if (!COLUMNS.contains(column)) {
                        throw new IllegalArgumentException(column);
                    }
                    jdbc.query("select " + column + " from items", mapper);
                    String direction = "desc".equals(order) && order.length() > 0 ? order : "asc";
                    jdbc.query("select * from items order by id " + direction, mapper);
                    if (COLUMNS.contains(order) || order.isEmpty()) {
                        jdbc.query("select * from items order by " + order, mapper); // @SQL
                    }
                }

                @PostMapping("/mapped")
                void mapped(@RequestBody OrderDto dto) {
                    OrderEntity entity = orderMapper.toEntity(dto);
                    jdbc.update("update orders set comment = '" + entity.getComment() + "'"); // @SQL
                }
            }

            class OrderService {
                void store(Order order) {
                    jdbc.update("insert into orders (comment) values ('" + order.getComment() + "')"); // @SQL
                    jdbc.update("insert into orders (id) values ('" + order.getId() + "')");
                }
            }

            class Order {
                private final String text;
                private String id;

                Order(String comment) {
                    this.text = comment;
                }

                String getComment() {
                    return text;
                }
            }

            record OrderRecord(String comment, int count) {
            }

            enum Status { NEW, DONE }
            """;

    @Test
    void tracesValuesAndUnderstandsChecks() {
        List<Violation> violations = RuleTests.check(new SqlConcatenationRule(), CODE);

        assertThat(violations).extracting(Violation::line).containsExactlyElementsOf(marked());
    }

    @Test
    void explainsThePath() {
        assertThat(RuleTests.check(new SqlConcatenationRule(), CODE)).extracting(Violation::message)
                // Конструктор Order кладет параметр comment в поле text, а читается оно геттером getComment()
                .anyMatch(message -> message.contains("через Order.text") || message.contains("через Order.comment"))
                .anyMatch(message -> message.contains("через toEntity(...)"));
    }

    private List<Integer> marked() {
        List<Integer> lines = new ArrayList<>();
        String[] code = CODE.split("\n");
        for (int index = 0; index < code.length; index++) {
            if (code[index].contains("// @SQL")) {
                lines.add(index + 1);
            }
        }
        return lines;
    }
}
