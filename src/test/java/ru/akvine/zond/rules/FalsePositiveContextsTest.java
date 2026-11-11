package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.codesmell.CommentedOutCodeRule;
import ru.akvine.zond.rules.codesmell.ConsoleOutputRule;
import ru.akvine.zond.rules.codesmell.MagicNumberRule;
import ru.akvine.zond.rules.codesmell.TooManyDependenciesRule;
import ru.akvine.zond.rules.exceptions.LostExceptionCauseRule;
import ru.akvine.zond.rules.logical.DefaultCharsetRule;
import ru.akvine.zond.rules.logical.NullInsteadOfEmptyCollectionRule;
import ru.akvine.zond.rules.performance.MapperPerCallRule;
import ru.akvine.zond.rules.resources.UnboundedExecutorRule;
import ru.akvine.zond.rules.security.HardcodedCredentialsRule;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные прогоном Zond по собственному коду: конструкция та же, что у настоящей проблемы,
 * но место, где она стоит, делает ее уместной
 */
class FalsePositiveContextsTest {

    @Test
    void constantThatDescribesSecretIsNotSecret() {
        assertThat(RuleTests.lines(new HardcodedCredentialsRule(), """
                class Codes {
                    static final String SECRET_IN_TO_STRING_RULE_CODE = "jr:372";
                    static final String PASSWORD_ERROR_MESSAGE = "Неверный пароль или логин";
                    static final String TOKEN_HEADER = "X-Auth-Token";
                    static final String API_TOKEN = "9f8a7c6b5d4e3f2a1b0c9d8e7f6a5b4c";
                }
                """)).containsExactly(5);
    }

    @Test
    void consoleInterfacePrintsToConsole() {
        String menu = """
                package app.cli;
                class MainMenu {
                    void show() {
                        System.out.println("1. Проверить проект");
                    }
                }
                """;
        String printer = """
                package app.report;
                class ConsolePrinter {
                    void print(String line) {
                        System.out.println(line);
                    }
                }
                """;
        String service = """
                package app.clients;
                class ClientService {
                    void load() {
                        System.out.println("loaded");
                    }
                }
                """;
        ConsoleOutputRule rule = new ConsoleOutputRule();

        assertThat(RuleTests.check(rule, Path.of("MainMenu.java"), menu)).isEmpty();
        assertThat(RuleTests.check(rule, Path.of("ConsolePrinter.java"), printer)).isEmpty();
        assertThat(RuleTests.check(rule, Path.of("ClientService.java"), service)).hasSize(1);
    }

    @Test
    void declarationInsideMethodIsIllustration() {
        assertThat(RuleTests.lines(new CommentedOutCodeRule(), """
                class PoolRule {
                    // private final Cache cache = new Cache();
                    private int size;
                    void check(Node node) {
                        // private static final int THREADS = 100;
                        visit(node);
                        // int limit = compute(node);
                        visit(node);
                    }
                }
                """)).containsExactly(2, 7);
    }

    @Test
    void positionIsNotMagicNumber() {
        assertThat(RuleTests.lines(new MagicNumberRule(), """
                class Menu {
                    void open(int choice, Matcher matcher, boolean reversed) {
                        switch (choice) {
                            case 1 -> scan();
                            case 2 -> report();
                            case 3 -> settings(matcher.group(3), matcher.group(reversed ? 4 : 3));
                            case 4 -> rules();
                            default -> exit();
                        }
                    }
                    String status(int code) {
                        return switch (code) {
                            case 200 -> "ok";
                            case 404 -> "missing";
                            case 500 -> "failed";
                            default -> "unknown";
                        };
                    }
                    boolean isLong(String name) {
                        return name.length() > 7;
                    }
                }
                """)).containsExactly(13, 14, 15, 20);
    }

    @Test
    void settingsAreNotDependencies() {
        String settings = """
                @Component
                class ReportSettings {
                    ReportSettings(@Value("${report.path:}") String path, @Value("${report.format:}") String format,
                            @Value("${report.title:}") String title, @Value("${report.author:}") String author,
                            @Value("${report.zone:}") String zone, @Value("${report.locale:}") String locale,
                            @Value("${report.limit:}") String limit, Environment environment) {
                    }
                }
                """;
        String service = """
                @Service
                class OrderService {
                    OrderService(OrderRepository orders, ItemRepository items, PriceService prices, StockService stock,
                            MailService mail, AuditService audit, UserService users, @Value("#{clock}") Clock clock) {
                    }
                }
                """;
        TooManyDependenciesRule rule = new TooManyDependenciesRule();

        assertThat(RuleTests.check(rule, settings)).isEmpty();
        assertThat(RuleTests.lines(rule, service)).containsExactly(1);
    }

    @Test
    void unwrappedCauseIsPassed() {
        assertThat(RuleTests.lines(new LostExceptionCauseRule(), """
                class Tasks {
                    Object await(Future<Object> future) {
                        try {
                            return future.get();
                        } catch (ExecutionException exception) {
                            throw new IllegalStateException("Задача завершилась с ошибкой", exception.getCause());
                        }
                    }
                    Object call(Method method) {
                        try {
                            return method.invoke(this);
                        } catch (InvocationTargetException exception) {
                            Throwable cause = exception.getTargetException();
                            throw new IllegalStateException("Вызов не удался", cause);
                        } catch (IllegalAccessException exception) {
                            throw new IllegalStateException("Метод недоступен: " + exception.getMessage());
                        }
                    }
                }
                """)).containsExactly(16);
    }

    @Test
    void consoleStreamKeepsConsoleCharset() {
        assertThat(RuleTests.lines(new DefaultCharsetRule(), """
                class Input {
                    private final BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
                    BufferedReader open(InputStream stream) {
                        return new BufferedReader(new InputStreamReader(stream));
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void poolOfSingleCallDoesNotGrow() {
        assertThat(RuleTests.lines(new UnboundedExecutorRule(), """
                class Loader {
                    private final ExecutorService shared = Executors.newFixedThreadPool(8);
                    List<Future<Unit>> parse(List<Path> files, int threads) {
                        ExecutorService executor = Executors.newFixedThreadPool(threads);
                        try {
                            return files.stream().map(file -> executor.submit(() -> parse(file))).toList();
                        } finally {
                            executor.shutdownNow();
                        }
                    }
                    void index(List<Path> files) {
                        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
                            files.forEach(file -> executor.submit(() -> parse(file)));
                        }
                    }
                    ExecutorService create() {
                        ExecutorService executor = Executors.newFixedThreadPool(4);
                        return executor;
                    }
                    void burst(List<Path> files) {
                        ExecutorService executor = Executors.newCachedThreadPool();
                        files.forEach(file -> executor.submit(() -> parse(file)));
                        executor.shutdown();
                    }
                }
                """)).containsExactly(2, 17, 21);
    }

    @Test
    void yamlIsNotSharedBetweenThreads() {
        assertThat(RuleTests.lines(new MapperPerCallRule(), """
                @Component
                class Parsers {
                    Object yaml(String text) {
                        return new Yaml().load(text);
                    }
                    Object json(String text) throws Exception {
                        return new ObjectMapper().readTree(text);
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void documentedNullIsContract() {
        assertThat(RuleTests.lines(new NullInsteadOfEmptyCollectionRule(), """
                class Ranges {
                    /**
                     * @param left левая граница
                     * @return границы диапазона; null, если сравнение истинным быть не может
                     */
                    long[] refine(long left, long right) {
                        if (left > right) {
                            return null;
                        }
                        return new long[] {left, right};
                    }
                    /**
                     * @param owner владелец, может быть null
                     * @return заказы владельца
                     */
                    List<Order> orders(User owner) {
                        if (owner.isBlocked()) {
                            return null;
                        }
                        return owner.orders();
                    }
                }
                """)).containsExactly(18);
    }
}
