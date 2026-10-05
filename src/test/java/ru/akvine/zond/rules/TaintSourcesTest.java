package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.CommandInjectionRule;
import ru.akvine.zond.rules.security.SqlConcatenationRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Источники данных извне помимо HTTP-запроса, проверки, которые делают значение безопасным,
 * и уверенность находки в зависимости от источника
 */
class TaintSourcesTest {
    private final SqlConcatenationRule rule = new SqlConcatenationRule();

    @Test
    void messageFromQueueIsExternalData() {
        List<Violation> violations = RuleTests.check(rule, """
                class Listener {
                    @KafkaListener(topics = "orders")
                    void on(String payload, ConsumerRecord<String, String> record) {
                        jdbc.query("select * from orders where id = '" + payload + "'", mapper);
                        jdbc.query("select * from orders where code = '" + record.value() + "'", mapper);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(4, 5);
        assertThat(violations.get(0).message()).contains("сообщение из очереди: payload");
        // Кто пишет в очередь, по коду не видно: находка вероятна, но не подтверждена
        assertThat(violations).extracting(Violation::confidence).containsOnly(Confidence.PROBABLE);
    }

    @Test
    void uploadedFileAndExternalResponseAreExternalData() {
        List<Violation> violations = RuleTests.check(rule, """
                @RestController
                class Import {
                    @PostMapping("/upload")
                    void upload(@RequestParam MultipartFile file) throws IOException {
                        String text = new String(file.getBytes());
                        jdbc.update("insert into notes values ('" + text + "')");
                    }
                    void sync(String url) {
                        String name = restTemplate.getForObject(url, String.class);
                        jdbc.update("update clients set name = '" + name + "'");
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(6, 10);
        assertThat(violations.get(0).message()).contains("содержимое загруженного файла: file.getBytes()");
        assertThat(violations.get(1).message()).contains("ответ внешнего сервиса: getForObject(...)");
        assertThat(violations).extracting(Violation::confidence)
                .containsExactly(Confidence.CONFIRMED, Confidence.PROBABLE);
    }

    @Test
    void valueIsTracedThroughObjectProperty() {
        List<Violation> violations = RuleTests.checkProject(rule, Map.of(
                "OrderController.java", """
                        @RestController
                        class OrderController {
                            @PostMapping("/orders")
                            void create(@RequestParam String comment, @RequestParam int count) {
                                Order order = new Order();
                                order.setComment(comment);
                                order.setTitle("Заказ");
                                service.save(order);
                            }
                        }
                        """,
                "OrderService.java", """
                        class OrderService {
                            void save(Order order) {
                                jdbc.update("insert into orders (comment) values ('" + order.getComment() + "')");
                                jdbc.update("insert into orders (title) values ('" + order.getTitle() + "')");
                            }
                            void rename(Client client) {
                                jdbc.update("update clients set comment = '" + client.getComment() + "'");
                            }
                        }
                        """));

        // В title кладут литерал; у Client свойство comment из запроса не заполняется
        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(3);
            assertThat(violation.message()).contains("comment через Order.comment");
            assertThat(violation.confidence()).isEqualTo(Confidence.PROBABLE);
        });
    }

    @Test
    void checkedOrConvertedValueIsSafe() {
        List<Violation> violations = RuleTests.check(rule, """
                @RestController
                class Search {
                    @GetMapping("/search")
                    void find(@RequestParam String sort, @RequestParam String id, @RequestParam String status,
                              @RequestParam @Pattern(regexp = "[a-z]+") String column, @RequestParam String raw) {
                        if (!ALLOWED.contains(sort)) {
                            throw new IllegalArgumentException("sort");
                        }
                        String q1 = "select * from items order by " + sort;
                        String q2 = "select * from items where id = " + Long.parseLong(id);
                        String q3 = "select * from items where status = '" + Status.valueOf(status) + "'";
                        String q4 = "select " + column + " from items";
                        if (raw.matches("[a-z]+")) {
                            String q5 = "select " + raw + " from items";
                        }
                        String q6 = "select " + raw + " from items";
                    }
                }
                """);

        // Список допустимых, приведение к числу и enum, @Pattern и проверка по шаблону снимают находку;
        // проверка, которая использование не охватывает, - нет
        assertThat(violations).extracting(Violation::line).containsExactly(16);
        assertThat(violations.get(0).confidence()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void findingWithoutSourceIsOnlySuspicion() {
        List<Violation> violations = RuleTests.check(new CommandInjectionRule(), """
                @RestController
                class Tools {
                    @GetMapping("/ping")
                    void ping(@RequestParam String host) throws IOException {
                        Runtime.getRuntime().exec("ping " + host);
                    }
                    void archive(String directory) throws IOException {
                        Runtime.getRuntime().exec("tar -czf backup.tgz " + directory);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::confidence)
                .containsExactly(Confidence.CONFIRMED, Confidence.SUSPICION);
    }
}
