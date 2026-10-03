package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Анализ хода выполнения: граф вызовов между классами, путь данных запроса и проверки, стоящие на пути к использованию
 */
class FlowAnalysisTest {
    private static final String PACKAGE = "demo";
    private static final String JAVA_EXTENSION = ".java";

    private static final String SENDER = """
            package demo;

            public class Sender {
                private RestTemplate restTemplate;

                public void send() {
                    restTemplate.postForObject("http://example.org", null, String.class);
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void findsHttpCallHiddenBehindCallChain() throws IOException {
        List<Violation> found = check(new CheckTransactionalHttpCallRule(), Map.of(
                "Sender", SENDER,
                "Notifier", """
                        package demo;

                        public class Notifier {
                            private Sender sender;

                            public void notifyClient() {
                                sender.send();
                            }
                        }
                        """,
                "Orders", """
                        package demo;

                        public class Orders {
                            private Notifier notifier;

                            @Transactional
                            public void place() {
                                notifier.notifyClient();
                            }

                            public void remind() {
                                notifier.notifyClient();
                            }
                        }
                        """));

        // Сообщаем о вызове в транзакционном методе; тот же вызов вне транзакции проблемой не является
        assertThat(found).singleElement().satisfies(violation -> {
            assertThat(place(violation)).isEqualTo("Orders:8");
            assertThat(violation.message())
                    .contains("restTemplate.postForObject (через вызов notifyClient -> send)")
                    .contains("@Transactional-метода 'place'");
        });
    }

    @Test
    void callOfInterfaceMethodLeadsToImplementation() throws IOException {
        List<Violation> found = check(new CheckTransactionalHttpCallRule(), Map.of(
                "Gateway", """
                        package demo;

                        public interface Gateway {
                            void push();
                        }
                        """,
                "HttpGateway", """
                        package demo;

                        public class HttpGateway implements Gateway {
                            private RestTemplate restTemplate;

                            @Override
                            public void push() {
                                restTemplate.getForObject("http://example.org", String.class);
                            }
                        }
                        """,
                "Orders", """
                        package demo;

                        public class Orders {
                            private Gateway gateway;

                            @Transactional
                            public void place() {
                                gateway.push();
                            }
                        }
                        """));

        assertThat(found).extracting(this::place).containsExactly("Orders:8");
    }

    @Test
    void asyncMethodRunsOutsideCallerTransaction() throws IOException {
        List<Violation> found = check(new CheckTransactionalHttpCallRule(), Map.of(
                "Sender", SENDER.replace("    public void send()", "    @Async\n    public void send()"),
                "Orders", """
                        package demo;

                        public class Orders {
                            private Sender sender;

                            @Transactional
                            public void place() {
                                sender.send();
                            }
                        }
                        """));

        assertThat(found).isEmpty();
    }

    @Test
    void callDepthIsConfigurable() throws IOException {
        CheckTransactionalHttpCallRule rule = new CheckTransactionalHttpCallRule();
        rule.setSettings(RuleSettings.of(Map.of("CheckTransactionalHttpCallRule.max-call-depth", "0")));

        List<Violation> found = check(rule, Map.of(
                "Sender", SENDER,
                "Orders", """
                        package demo;

                        public class Orders {
                            private Sender sender;

                            @Transactional
                            public void place() {
                                sender.send();
                            }
                        }
                        """));

        assertThat(found).isEmpty();
    }

    @Test
    void findsRepositoryCallHiddenInMethodCalledFromLoop() {
        // Один файл без решателя типов: вызовы внутри класса находятся по именам
        List<Violation> found = RuleTests.check(new CheckRepositoryCallInLoopRule(), """
                class Orders {
                    void enrichAll(List<Order> orders) {
                        for (Order order : orders) {
                            enrich(order);
                            print(order);
                        }
                        enrich(orders.get(0));
                    }

                    void enrich(Order order) {
                        order.setCustomer(load(order.getCustomerId()));
                    }

                    Customer load(Long id) {
                        return customerRepository.findById(id).orElseThrow();
                    }

                    void print(Order order) {
                        System.out.println(order);
                    }
                }
                """);

        assertThat(found).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(4);
            assertThat(violation.message())
                    .contains("'enrich(...)' в цикле")
                    .contains("customerRepository.findById")
                    .contains("через вызов enrich -> load");
        });
    }

    @Test
    void followsRequestDataThroughVariablesAndCalls() throws IOException {
        List<Violation> found = check(new CheckPathTraversalRule(), Map.of(
                "Storage", """
                        package demo;

                        import java.io.File;

                        public class Storage {
                            public File load(String name) {
                                String path = "/data/" + name.trim();
                                return new File(path);
                            }

                            public File loadById(Long id) {
                                return new File("/data/" + id);
                            }

                            public File loadDefault(String name) {
                                return new File("/data/" + name);
                            }
                        }
                        """,
                "FilesController", """
                        package demo;

                        import java.io.File;

                        @RestController
                        public class FilesController {
                            private Storage storage;

                            @GetMapping("/files")
                            public File download(@RequestParam String file, @RequestParam Long id) {
                                storage.loadById(id);
                                storage.loadDefault("readme.txt");
                                return storage.load(file);
                            }
                        }
                        """));

        // Параметр запроса дошел до new File(...) через вызов, обработку строки и переменную.
        // Число и постоянная строка путь испортить не могут
        assertThat(found).singleElement().satisfies(violation -> {
            assertThat(place(violation)).isEqualTo("Storage:8");
            assertThat(violation.message()).contains("'file из метода download'");
        });
    }

    @Test
    void unannotatedStringParameterOfHandlerIsRequestData() {
        assertThat(RuleTests.lines(new CheckOpenRedirectRule(), """
                class Pages {
                    @GetMapping("/go")
                    String go(String target, Long id) {
                        String address = target;
                        return "redirect:" + address;
                    }

                    String internal(String target) {
                        return "redirect:" + target;
                    }
                }
                """)).containsExactly(5);
    }

    @Test
    void sqlConcatenationTellsWhenValueComesFromRequest() {
        assertThat(RuleTests.check(new CheckSqlConcatenationRule(), """
                class Users {
                    @GetMapping("/users")
                    List<User> find(@RequestParam String name) {
                        return jdbc.query("select * from users where name = '" + name + "'", mapper);
                    }
                }
                """)).singleElement().satisfies(violation ->
                assertThat(violation.message()).contains("name (данные запроса: name)"));
    }

    @Test
    void sizeCheckMustStandOnTheWayToAccess() {
        assertThat(RuleTests.lines(new CheckListGetFirstWithoutCheckRule(), """
                class Sample {
                    void exitsEarly(List<String> list) {
                        if (list.isEmpty()) {
                            return;
                        }
                        use(list.get(0));
                    }

                    void checksTooLate(List<String> list) {
                        use(list.get(0));
                        if (list.isEmpty()) {
                            return;
                        }
                    }

                    void checksButGoesOn(List<String> list) {
                        if (list.isEmpty()) {
                            log("empty");
                        }
                        use(list.get(0));
                    }

                    void checksOtherList(List<String> list, List<String> other) {
                        if (other.isEmpty()) {
                            return;
                        }
                        use(list.get(0));
                    }

                    void guardsInExpression(List<String> list) {
                        String first = list.isEmpty() ? null : list.get(0);
                        boolean filled = !list.isEmpty() && list.get(0) != null;
                    }

                    void keepsCheckInVariable(List<String> list) {
                        boolean empty = list.isEmpty();
                        if (empty) {
                            throw new IllegalStateException();
                        }
                        use(list.get(0));
                    }

                    void checksInsideBranch(List<String> list) {
                        if (list.size() == 1) {
                            use(list.get(0));
                        }
                    }
                }
                """)).containsExactly(10, 20, 27);
    }

    @Test
    void presenceCheckMustStandOnTheWayToGet() {
        assertThat(RuleTests.lines(new CheckOptionalMisuseRule(), """
                class Sample {
                    void checked(Optional<String> value) {
                        if (value.isPresent()) {
                            use(value.get());
                        }
                    }

                    void checkedTooLate(Optional<String> value) {
                        use(value.get());
                        if (value.isEmpty()) {
                            return;
                        }
                    }
                }
                """)).containsExactly(9);
    }

    @Test
    void hasNextMustStandOnTheWayToNext() {
        assertThat(RuleTests.lines(new CheckIteratorNextWithoutHasNextRule(), """
                class Sample {
                    void loop(Iterator<String> iterator) {
                        while (iterator.hasNext()) {
                            use(iterator.next());
                        }
                    }

                    void first(Iterator<String> iterator) {
                        use(iterator.next());
                        if (iterator.hasNext()) {
                            use("more");
                        }
                    }
                }
                """)).containsExactly(9);
    }

    /**
     * @param files имя класса -> исходный код; файлы кладутся в каталог пакета demo и загружаются с решателем типов
     */
    private List<Violation> check(ProjectRule rule, Map<String, String> files) throws IOException {
        Path packageDirectory = Files.createDirectories(dir.resolve(PACKAGE));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(packageDirectory.resolve(file.getKey() + JAVA_EXTENSION), file.getValue());
        }

        List<SourceFile> sources = new FileSystemSourceLoader().load(dir).sources();
        return rule.checkProject(sources).stream()
                .sorted(Comparator.comparing(this::place))
                .toList();
    }

    // Класс:строка
    private String place(Violation violation) {
        return violation.file().getFileName().toString().replace(JAVA_EXTENSION, "") + ":" + violation.line();
    }
}
