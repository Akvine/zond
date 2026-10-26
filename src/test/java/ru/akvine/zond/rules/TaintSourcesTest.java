package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.CommandInjectionRule;
import ru.akvine.zond.rules.security.ResponseWriteWithoutEscapingRule;
import ru.akvine.zond.rules.security.SqlConcatenationRule;

import java.nio.file.Path;
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
    void propertyWrittenInSameMethodIsWhatIsRead() {
        List<Violation> violations = RuleTests.checkProject(rule, Map.of(
                "OrderController.java", """
                        @RestController
                        class OrderController {
                            @PostMapping("/orders")
                            void create(@RequestParam String comment) {
                                Order order = new Order();
                                order.setComment(comment);
                                service.save(order);
                            }
                            @PostMapping("/drafts")
                            void draft(@RequestParam String comment) {
                                Order draft = new Order();
                                draft.setComment("черновик");
                                jdbc.update("insert into drafts values ('" + draft.getComment() + "')");
                                Order empty = new Order();
                                jdbc.update("insert into drafts values ('" + empty.getComment() + "')");
                                Order copy = new Order();
                                copy.setComment(comment);
                                jdbc.update("insert into drafts values ('" + copy.getComment() + "')");
                            }
                        }
                        """));

        // В comment заказов пишут данные запроса, но draft и empty - свои объекты метода: в draft записана
        // константа, в empty ничего. copy - тот же объект, куда записан запрос: находка подтверждена
        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(18);
            assertThat(violation.message()).contains("comment через Order.comment");
            assertThat(violation.confidence()).isEqualTo(Confidence.CONFIRMED);
        });
    }

    @Test
    void parameterSummaryDependsOnSink() {
        List<SourceFile> sources = List.of(
                RuleTests.parse(Path.of("Controller.java"), """
                        @RestController
                        class Controller {
                            private final Helper helper = new Helper();
                            @GetMapping("/a")
                            void a(@RequestParam String name) {
                                helper.show(HtmlUtils.htmlEscape(name));
                            }
                        }
                        """),
                RuleTests.parse(Path.of("Helper.java"), """
                        class Helper {
                            void show(String text) throws IOException {
                                response.getWriter().write(text);
                                jdbc.query("select * from t where n = '" + text + "'", mapper);
                            }
                        }
                        """));

        // Сводка параметра text для ответа (экранирован) не годится для SQL: правила работают на одних файлах
        assertThat(new ResponseWriteWithoutEscapingRule().checkProject(sources)).isEmpty();
        assertThat(rule.checkProject(sources)).extracting(Violation::line).containsExactly(4);
    }

    @Test
    void propertyOfJdkObjectDoesNotCarryDataBetweenMethods() {
        List<Violation> violations = RuleTests.checkProject(rule, Map.of(
                "WorkerController.java", """
                        @RestController
                        class WorkerController {
                            @PostMapping("/workers")
                            void start(@RequestParam String name) {
                                Thread worker = new Thread(task);
                                worker.setName(name);
                            }
                        }
                        """,
                "Audit.java", """
                        class Audit {
                            void log(Thread thread) {
                                jdbc.update("insert into audit (thread) values ('" + thread.getName() + "')");
                            }
                        }
                        """));

        // Имя одного потока из запроса ничего не говорит об имени другого потока
        assertThat(violations).isEmpty();
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
