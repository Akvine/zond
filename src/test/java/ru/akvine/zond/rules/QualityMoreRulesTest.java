package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.AssertTrueEqualsRule;
import ru.akvine.zond.rules.codesmell.CommentedOutCodeRule;
import ru.akvine.zond.rules.codesmell.CyclomaticComplexityRule;
import ru.akvine.zond.rules.codesmell.DuplicateStringLiteralRule;
import ru.akvine.zond.rules.codesmell.LoggerWrongClassRule;
import ru.akvine.zond.rules.codesmell.RedundantBooleanReturnRule;
import ru.akvine.zond.rules.codesmell.TryFailRule;
import ru.akvine.zond.rules.codesmell.UnusedLocalVariableRule;
import ru.akvine.zond.rules.exceptions.GenericExceptionRule;
import ru.akvine.zond.rules.exceptions.LogAndRethrowRule;
import ru.akvine.zond.rules.exceptions.LostStackTraceInLogRule;
import ru.akvine.zond.rules.logical.IncompleteAssertionRule;
import ru.akvine.zond.rules.logical.LogPlaceholderMismatchRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:187 - jr:199: исключения, логирование, качество кода и тесты
 */
class QualityMoreRulesTest {

    @Test
    void lostStackTraceInLog() {
        assertThat(RuleTests.lines(new LostStackTraceInLogRule(), """
                class Sample {
                    void run() {
                        try {
                            work();
                        } catch (IOException e) {
                            log.error("Failed: " + e.getMessage());
                            log.error("Failed: {}", e.getMessage());
                            log.error("Failed: {}", e.getMessage(), e);
                            log.error("Failed", e);
                            log.debug("Details: {}", e.getMessage());
                        }
                    }
                }
                """)).containsExactly(6, 7);
    }

    @Test
    void logPlaceholderMismatch() {
        List<Violation> violations = RuleTests.check(new LogPlaceholderMismatchRule(), """
                class Sample {
                    void run(Long id, String name, Exception e, Object[] values) {
                        log.info("User {} {}", id);
                        log.info("User {}", id, name);
                        log.info("User {} {}", id, name);
                        log.error("Failed {}", id, e);
                        log.info("Values {} {}", values);
                        log.info("Done");
                        log.info(message, id);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4);
        assertThat(violations.get(0).message()).contains("2 подстановок", "аргументов 1");
        assertThat(violations.get(1).message()).contains("1 подстановок", "аргументов 2");
    }

    @Test
    void loggerWrongClass() {
        assertThat(RuleTests.lines(new LoggerWrongClassRule(), """
                class OrderService {
                    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
                    class Inner {
                        private final Logger inner = LoggerFactory.getLogger(OrderService.class);
                    }
                }
                class PaymentService {
                    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
                }
                """)).containsExactly(2);
    }

    @Test
    void genericException() {
        assertThat(RuleTests.lines(new GenericExceptionRule(), """
                class Sample {
                    void run() throws Exception {
                        throw new RuntimeException("failed");
                    }
                    void specific() throws IOException {
                        throw new IllegalStateException("failed");
                    }
                    @Override
                    public void inherited() throws Exception {}
                }
                """)).containsExactly(2, 3);
    }

    @Test
    void logAndRethrow() {
        assertThat(RuleTests.lines(new LogAndRethrowRule(), """
                class Sample {
                    void run() {
                        try {
                            work();
                        } catch (IOException e) {
                            log.error("Failed", e);
                            throw new IllegalStateException(e);
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            log.error("Failed", e);
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                """)).containsExactly(6);
    }

    @Test
    void unusedLocalVariable() {
        assertThat(RuleTests.lines(new UnusedLocalVariableRule(), """
                class Sample {
                    int run(List<String> items) {
                        int leftover = 5;
                        int used = 1;
                        String name = "x";
                        items.forEach(name::equals);
                        String ignored = load();
                        return used;
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void cyclomaticComplexity() {
        String code = "class Sample {\n"
                + "    void complex(int a) {\n"
                + "        if (a > 0) { work(); }\n".repeat(11)
                + "    }\n"
                + "    void simple(int a) {\n"
                + "        if (a > 0) { work(); }\n"
                + "    }\n"
                + "}\n";

        List<Violation> violations = RuleTests.check(new CyclomaticComplexityRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(2);
        assertThat(violations.get(0).message()).contains("'complex'", "- 12 ");
    }

    @Test
    void commentedOutCode() {
        assertThat(RuleTests.lines(new CommentedOutCodeRule(), """
                class Sample {
                    void run() {
                        // service.process(order);
                        // if (ready) {
                        //     start();
                        // }
                        work(); // обычный комментарий
                        // Объясняем, зачем нужен вызов ниже.
                        /* return value; */
                        work();
                    }
                }
                """)).containsExactly(3, 9);
    }

    @Test
    void duplicateStringLiteral() {
        List<Violation> violations = RuleTests.check(new DuplicateStringLiteralRule(), """
                class Sample {
                    private static final String CONSTANT = "constant value";
                    void run() {
                        send("order-created");
                        send("order-created");
                        send("order-created");
                        send("once only");
                        send("id");
                        send("id");
                        send("id");
                        use(CONSTANT, "constant value", "constant value");
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(4);
        assertThat(violations.get(0).message()).contains("order-created", "3 раз");
    }

    @Test
    void redundantBooleanReturn() {
        assertThat(RuleTests.lines(new RedundantBooleanReturnRule(), """
                class Sample {
                    boolean first(int a) {
                        if (a > 0) {
                            return true;
                        } else {
                            return false;
                        }
                    }
                    boolean second(int a) {
                        return a > 0 ? false : true;
                    }
                    boolean third(int a) {
                        if (a > 0) {
                            return true;
                        }
                        return compute(a);
                    }
                }
                """)).containsExactly(3, 10);
    }

    @Test
    void incompleteAssertion() {
        assertThat(RuleTests.lines(new IncompleteAssertionRule(), """
                class SampleTest {
                    @Test
                    void run() {
                        assertThat(service.load());
                        assertThat(service.load()).isNotNull();
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void assertTrueEquals() {
        assertThat(RuleTests.lines(new AssertTrueEqualsRule(), """
                class SampleTest {
                    @Test
                    void run() {
                        assertTrue(actual.equals(expected));
                        assertFalse(a == b);
                        assertTrue(service.isReady());
                        assertTrue(value != null);
                        assertEquals(expected, actual);
                    }
                }
                """)).containsExactly(4, 5);
    }

    @Test
    void tryFail() {
        assertThat(RuleTests.lines(new TryFailRule(), """
                class SampleTest {
                    @Test
                    void run() {
                        try {
                            service.load();
                            fail("expected exception");
                        } catch (IllegalStateException e) {
                            assertEquals("x", e.getMessage());
                        }
                        try {
                            service.load();
                        } catch (IllegalStateException e) {
                            log(e);
                        }
                    }
                }
                """)).containsExactly(4);
    }
}
