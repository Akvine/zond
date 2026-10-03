package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;

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

        List<Violation> violations = RuleTests.check(new CheckLongMethodRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(2);
        assertThat(violations.get(0).message()).contains("'big'", "57 строк");
    }

    @Test
    void deepNesting() {
        assertThat(RuleTests.lines(new CheckDeepNestingRule(), """
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

        List<Violation> violations = RuleTests.check(new CheckLargeClassRule(), code);

        assertThat(violations).extracting(Violation::line).containsExactly(1);
        assertThat(violations.get(0).message()).contains("'Big'", "31 методов");
    }

    @Test
    void tooManyDependencies() {
        assertThat(RuleTests.lines(new CheckTooManyDependenciesRule(), """
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
        assertThat(RuleTests.lines(new CheckBooleanFlagParameterRule(), """
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
        assertThat(RuleTests.lines(new CheckProxyAnnotationOnPrivateMethodRule(), """
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
        assertThat(RuleTests.lines(new CheckScheduledWithParametersRule(), """
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
        assertThat(RuleTests.lines(new CheckAsyncReturnTypeRule(), """
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
        assertThat(RuleTests.lines(new CheckValueWithoutDefaultRule(), """
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
                """)).containsExactly(2, 8);
    }

    @Test
    void testWithoutAssertion() {
        assertThat(RuleTests.lines(new CheckTestWithoutAssertionRule(), """
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
        assertThat(RuleTests.lines(new CheckDisabledTestWithoutReasonRule(), """
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
        assertThat(RuleTests.lines(new CheckSleepInTestRule(), """
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
