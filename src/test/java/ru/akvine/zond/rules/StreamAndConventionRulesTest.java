package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.rules.logical.PureCallResultIgnoredRule;
import ru.akvine.zond.rules.logical.ReadLoopWithoutEofRule;
import ru.akvine.zond.rules.logical.UsuallyCheckedResultIgnoredRule;
import ru.akvine.zond.rules.security.CommandInjectionRule;
import ru.akvine.zond.rules.security.SqlConcatenationRule;
import ru.akvine.zond.rules.support.TaintLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:453 и jr:454, необоснованные находки jr:138 и справочник источников данных
 */
class StreamAndConventionRulesTest {
    @TempDir
    Path dir;

    @Test
    void readLoopWithoutEndOfStreamCheck() {
        assertThat(RuleTests.lines(new ReadLoopWithoutEofRule(), """
                class Reader {
                    void copy(InputStream in, OutputStream out, byte[] buffer) throws IOException {
                        int n;
                        while ((n = in.read(buffer)) != 0) {
                            out.write(buffer, 0, n);
                        }
                    }
                    void fill(InputStream in, byte[] buffer, int length) throws IOException {
                        int remaining = length;
                        while (remaining > 0) {
                            int n = in.read(buffer, length - remaining, remaining);
                            remaining -= n;
                        }
                    }
                    void skipAll(InputStream in, long count) throws IOException {
                        long toSkip = count;
                        while (toSkip > 0) {
                            toSkip -= in.skip(toSkip);
                        }
                    }
                    void safe(InputStream in, OutputStream out, byte[] buffer, int length) throws IOException {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                        }
                        int remaining = length;
                        while (remaining > 0) {
                            int read = in.read(buffer, 0, remaining);
                            if (read < 0) {
                                throw new EOFException();
                            }
                            remaining -= read;
                        }
                        long toSkip = length;
                        while (toSkip > 0) {
                            long skipped = in.skip(toSkip);
                            if (skipped == 0) {
                                break;
                            }
                            toSkip -= skipped;
                        }
                    }
                }
                """)).containsExactly(4, 11, 18);
    }

    @Test
    void resultIgnoredWhereProjectChecksIt() {
        String validator = """
                class OrderValidator {
                    boolean check(Order order) {
                        return order.total() > 0;
                    }
                    OrderValidator strict() {
                        return this;
                    }
                }
                """;
        String service = """
                class OrderService {
                    private final OrderValidator validator = new OrderValidator();
                    void a(Order order) { if (validator.check(order)) { save(order); } }
                    void b(Order order) { boolean ok = validator.check(order); log(ok); }
                    void c(Order order) { if (!validator.check(order)) { return; } save(order); }
                    void d(Order order) { send(validator.check(order)); }
                    void e(Order order) { validator.check(order); save(order); }
                    void f(Order order) { validator.strict(); validator.strict(); validator.strict(); }
                    void g(Order order) { validator.strict(); validator.strict(); }
                    private Connection conn;
                    Connection connection() { if (conn == null) { conn = open(); } return conn; }
                    void h() { connection(); use(connection()); use(connection()); use(connection()); use(connection()); }
                }
                """;

        // check() проверяют в 4 из 5 вызовов - пятый подозрителен; strict() возвращает сам объект, connection()
        // отдельной строкой заполняет поле
        assertThat(RuleTests.checkProject(new UsuallyCheckedResultIgnoredRule(),
                Map.of("OrderValidator.java", validator, "OrderService.java", service)))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("OrderService.java:7");
    }

    @Test
    void throwingChecksAndSwitchValuesAreNotIgnoredResults() {
        String guard = """
                class Guard {
                    Order requireOrder(long id) { return find(id); }
                    Order order(long id) { return find(id); }
                    void a() { use(requireOrder(1)); use(requireOrder(2)); use(requireOrder(3)); use(requireOrder(4)); requireOrder(5); }
                    void b() { use(order(1)); use(order(2)); use(order(3)); use(order(4)); }
                    Order c(int kind) {
                        return switch (kind) {
                            case 1 -> order(5);
                            default -> null;
                        };
                    }
                }
                """;

        // requireOrder(5) бросает исключение, если заказа нет: результат здесь не нужен; order(5) - значение switch
        assertThat(RuleTests.checkProject(new UsuallyCheckedResultIgnoredRule(), Map.of("Guard.java", guard))).isEmpty();
    }

    @Test
    void ignoredResultOfMethodWithoutSideEffects() {
        String names = """
                class Names {
                    private final List<String> seen = new ArrayList<>();
                    private int calls;
                    String normalize(String name) { return name.trim().toLowerCase(); }
                    boolean isAllowed(String name, int limit) { return name.length() < limit && !name.isBlank(); }
                    String remember(String name) { seen.add(name); return name; }
                    String count(String name) { calls++; return name; }
                    String require(String name) { if (name == null) { throw new IllegalArgumentException(); } return name; }
                    String getName() { return "n"; }
                    String total(List<Integer> items) { int sum = 0; for (int item : items) { sum += item; } return String.valueOf(sum); }
                    void use(String name, List<Integer> items) {
                        normalize(name);
                        isAllowed(name, 10);
                        remember(name);
                        count(name);
                        require(name);
                        getName();
                        total(items);
                        name = normalize(name);
                    }
                }
                """;
        String labels = """
                class Labels {
                    void run(Base base) {
                        base.label("a");
                    }
                }
                class Base {
                    String label(String text) { return text; }
                }
                class Loud extends Base {
                    @Override
                    String label(String text) { System.out.println(text); return text; }
                }
                """;

        // remember() и count() меняют поля, require() бросает исключение, getName() - геттер; base.label(...) может
        // попасть в Loud.label(...), который печатает
        assertThat(RuleTests.checkProject(new PureCallResultIgnoredRule(), Map.of("Names.java", names, "Labels.java", labels)))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactlyInAnyOrder("Names.java:12", "Names.java:13", "Names.java:18");
    }

    @Test
    void commandBuiltOnlyFromOwnValuesIsNotReported() {
        assertThat(RuleTests.lines(new CommandInjectionRule(), """
                class Tools {
                    private static final String SCRIPT = "/opt/backup.sh";
                    @Value("${backup.target}")
                    private String target;
                    private String mutable;
                    void backup(String directory) throws IOException {
                        String cmd = Utils.osCommand();
                        String bar = "safe";
                        Runtime.getRuntime().exec(cmd + bar);
                        Runtime.getRuntime().exec(SCRIPT + " " + target);
                        Runtime.getRuntime().exec(SCRIPT + " " + directory);
                        Runtime.getRuntime().exec(SCRIPT + " " + mutable);
                    }
                }
                """)).containsExactly(11, 12);
    }

    @Test
    void ownSourcesFromTaintLibraryFile() throws IOException {
        Path library = Files.writeString(dir.resolve("taint-extra.properties"), "request-methods=getVertxParam\n");
        TaintLibrary.extend(library);

        // Свой фреймворк: routingContext.getVertxParam("id") - тоже данные запроса
        assertThat(RuleTests.lines(new SqlConcatenationRule(), """
                class Handler {
                    void handle(RoutingContext context) {
                        String id = context.getVertxParam("id");
                        jdbc.query("select * from orders where id = '" + id + "'", mapper);
                    }
                }
                """)).containsExactly(4);
    }
}
