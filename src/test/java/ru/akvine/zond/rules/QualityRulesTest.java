package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.rules.codesmell.BooleanFlagParameterRule;
import ru.akvine.zond.rules.codesmell.DeepNestingRule;
import ru.akvine.zond.rules.codesmell.DisabledTestWithoutReasonRule;
import ru.akvine.zond.rules.codesmell.LargeClassRule;
import ru.akvine.zond.rules.codesmell.LongMethodRule;
import ru.akvine.zond.rules.codesmell.SleepInTestRule;
import ru.akvine.zond.rules.codesmell.TestWithoutAssertionRule;
import ru.akvine.zond.rules.codesmell.TooManyDependenciesRule;
import ru.akvine.zond.rules.codesmell.ValueWithoutDefaultRule;
import ru.akvine.zond.rules.concurrency.AsyncReturnTypeRule;
import ru.akvine.zond.rules.logical.ProxyAnnotationOnPrivateMethodRule;
import ru.akvine.zond.rules.logical.ScheduledWithParametersRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:112, jr:113, jr:123 - jr:125, jr:136, jr:141 - jr:146: сложность, Spring-аннотации, тесты
 */
class QualityRulesTest {

    @Test
    void longMethod() {
        String code = "class Sample {\n"
                + "    void big() {\n"
                + "        work();\n".repeat(55)
                + "    }\n"
                + "    void small() {\n"
                + "        work();\n"
                + "    }\n"
                + "}\n";

        List<Violation> violations = RuleTests.check(new LongMethodRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(2);
        assertThat(violations.get(0).message()).contains("'big'", "57 строк");
    }

    @Test
    void deepNesting() {
        assertThat(RuleTests.lines(new DeepNestingRule(), """
                class Sample {
                    void run(List<String> items, boolean a) {
                        for (String item : items) {
                            if (a) {
                                while (a) {
                                    try {
                                        if (item.isEmpty()) {
                                            if (a) {
                                                work();
                                            }
                                        } else if (a) {
                                            work();
                                        }
                                    } finally {
                                        work();
                                    }
                                }
                            }
                        }
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void largeClass() {
        String code = "class Big {\n" + "    void method() {}\n".repeat(31) + "}\nclass Small {\n    void method() {}\n}\n";

        List<Violation> violations = RuleTests.check(new LargeClassRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(1);
        assertThat(violations.get(0).message()).contains("'Big'", "31 методов");
    }

    @Test
    void tooManyDependencies() {
        assertThat(RuleTests.lines(new TooManyDependenciesRule(), """
                @Service
                class Big {
                    Big(A a, B b, C c, D d, E e, F f, G g, H h) {}
                }
                @Service
                @RequiredArgsConstructor
                class Small {
                    private final A a;
                    private final B b;
                }
                class NotBean {
                    NotBean(A a, B b, C c, D d, E e, F f, G g, H h) {}
                }
                """)).containsExactly(1);
    }

    @Test
    void booleanFlagParameter() {
        assertThat(RuleTests.lines(new BooleanFlagParameterRule(), """
                class Sample {
                    public void process(Order order, boolean notify) {}
                    public void setActive(boolean active) {}
                    private void helper(Order order, boolean flag) {}
                    @Override
                    public void inherited(Order order, boolean flag) {}
                }
                """)).containsExactly(2);
    }

    @Test
    void proxyAnnotationOnPrivateMethod() {
        assertThat(RuleTests.lines(new ProxyAnnotationOnPrivateMethodRule(), """
                class Sample {
                    @Async
                    private void send() {}
                    @Cacheable("users")
                    private User load() { return null; }
                    @Async
                    public void ok() {}
                    @Transactional
                    private void tx() {}
                }
                """)).containsExactly(2, 4);
    }

    @Test
    void scheduledWithParameters() {
        assertThat(RuleTests.lines(new ScheduledWithParametersRule(), """
                class Sample {
                    @Scheduled(fixedRate = 1000)
                    public void bad(String name) {}
                    @Scheduled(fixedRate = 1000)
                    public void ok() {}
                }
                """)).containsExactly(2);
    }

    @Test
    void asyncReturnType() {
        assertThat(RuleTests.lines(new AsyncReturnTypeRule(), """
                class Sample {
                    @Async
                    public String bad() { return ""; }
                    @Async
                    public void ok1() {}
                    @Async
                    public CompletableFuture<String> ok2() { return null; }
                    public String plain() { return ""; }
                }
                """)).containsExactly(2);
    }

    @Test
    void valueWithoutDefault() {
        // Файлы настроек в проверку не попали: правило судит только по самой аннотации
        ScanContext context = new ScanContext(Path.of("."), List.of(RuleTests.parse(Path.of("Sample.java"), """
                class Sample {
                    @Value("${app.name}")
                    private String name;
                    @Value("${app.timeout:30}")
                    private int timeout;
                    @Value("#{systemProperties['user.home']}")
                    private String home;
                    @Value("${app.url}/api")
                    private String url;
                }
                """)), List.of(), List.of());

        assertThat(new ValueWithoutDefaultRule().checkContext(context)).extracting(Violation::line).containsExactly(2, 8);
    }

    @Test
    void testWithoutAssertion() {
        assertThat(RuleTests.lines(new TestWithoutAssertionRule(), """
                class SampleTest {
                    @Test
                    void empty() {
                        service.run();
                    }
                    @Test
                    void asserts() {
                        assertThat(service.run()).isTrue();
                    }
                    @Test
                    void verifies() {
                        service.run();
                        verify(mock).call();
                    }
                    @Test
                    void contextLoads() {
                    }
                    void helper() {
                        service.run();
                    }
                }
                """)).containsExactly(2);
    }

    @Test
    void disabledTestWithoutReason() {
        assertThat(RuleTests.lines(new DisabledTestWithoutReasonRule(), """
                class SampleTest {
                    @Disabled
                    @Test
                    void first() {}
                    @Disabled("flaky, see TASK-1")
                    @Test
                    void second() {}
                    @Ignore
                    @Test
                    void third() {}
                }
                """)).containsExactly(2, 8);
    }

    @Test
    void sleepInTest() {
        assertThat(RuleTests.lines(new SleepInTestRule(), """
                class SampleTest {
                    @Test
                    void waits() throws Exception {
                        Thread.sleep(1000);
                        TimeUnit.SECONDS.sleep(1);
                    }
                }
                class Worker {
                    void run() throws Exception {
                        Thread.sleep(1000);
                    }
                }
                """)).containsExactly(4, 5);
    }
}
