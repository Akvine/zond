package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.rules.codesmell.BooleanLiteralComparisonRule;
import ru.akvine.zond.rules.codesmell.ConstantInterfaceRule;
import ru.akvine.zond.rules.codesmell.HardcodedActiveProfileRule;
import ru.akvine.zond.rules.codesmell.NestedTernaryRule;
import ru.akvine.zond.rules.codesmell.UnusedMockRule;
import ru.akvine.zond.rules.codesmell.UtilityClassConstructorRule;
import ru.akvine.zond.rules.logical.ConstantAssertionRule;
import ru.akvine.zond.rules.logical.CurrentTimeMillisForDurationRule;
import ru.akvine.zond.rules.logical.EqualsOnPossibleNullRule;
import ru.akvine.zond.rules.logical.NullInsteadOfEmptyCollectionRule;
import ru.akvine.zond.rules.performance.MissingLimitsInConfigRule;
import ru.akvine.zond.rules.performance.SpringBootTestWithoutContextRule;
import ru.akvine.zond.rules.security.DebugSettingsInConfigRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:253 - jr:266: файлы настроек, качество кода, тесты
 */
class QualityTestAndConfigRulesTest {
    @TempDir
    Path dir;

    @Test
    void configRules() throws IOException {
        Files.writeString(dir.resolve("application.properties"), """
                spring.jpa.show-sql=true
                logging.level.root=DEBUG
                spring.h2.console.enabled=false
                spring.profiles.active=dev
                spring.datasource.url=jdbc:postgresql://db/app
                spring.servlet.multipart.enabled=true
                """);
        // В профиле разработки отладочные режимы уместны
        Files.writeString(dir.resolve("application-dev.properties"), "spring.jpa.show-sql=true\n");

        assertThat(check(new DebugSettingsInConfigRule()))
                .containsExactly("application.properties:1", "application.properties:2");
        assertThat(check(new HardcodedActiveProfileRule())).containsExactly("application.properties:4");
        assertThat(check(new MissingLimitsInConfigRule()))
                .containsExactly("application.properties:5", "application.properties:6");
    }

    @Test
    void configWithLimitsAndProfileFromEnvironment() throws IOException {
        Files.writeString(dir.resolve("application.properties"), """
                spring.profiles.active=${SPRING_PROFILE:prod}
                spring.datasource.url=jdbc:postgresql://db/app
                spring.datasource.hikari.maximum-pool-size=20
                spring.servlet.multipart.max-file-size=10MB
                """);

        assertThat(check(new HardcodedActiveProfileRule())).isEmpty();
        assertThat(check(new MissingLimitsInConfigRule())).isEmpty();
    }

    @Test
    void nullInsteadOfEmptyCollection() {
        assertThat(RuleTests.lines(new NullInsteadOfEmptyCollectionRule(), """
                class Sample {
                    List<String> names(boolean any) {
                        if (!any) {
                            return null;
                        }
                        return List.of();
                    }
                    String name() {
                        return null;
                    }
                    int[] numbers() {
                        return null;
                    }
                }
                """)).containsExactly(4, 12);
    }

    @Test
    void equalsOnPossibleNull() {
        assertThat(RuleTests.lines(new EqualsOnPossibleNullRule(), """
                class Sample {
                    boolean run(String status, Order order) {
                        boolean a = status.equals("NEW");
                        boolean b = "NEW".equals(status);
                        boolean c = order.getStatus().equals("NEW");
                        boolean d = status != null && status.equals("NEW");
                        boolean e = order.toString().equals("x");
                        String local = "x";
                        return local.equals("x");
                    }
                }
                """)).containsExactly(3, 5);
    }

    @Test
    void nestedTernary() {
        assertThat(RuleTests.lines(new NestedTernaryRule(), """
                class Sample {
                    void run(int x) {
                        int sign = x > 0 ? 1 : x < 0 ? -1 : 0;
                        int flag = x > 0 ? 1 : 0;
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void booleanLiteralComparison() {
        assertThat(RuleTests.lines(new BooleanLiteralComparisonRule(), """
                class Sample {
                    void run(boolean flag) {
                        if (flag == true) {}
                        if (flag != false) {}
                        if (flag) {}
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void constantInterface() {
        assertThat(RuleTests.lines(new ConstantInterfaceRule(), """
                interface Codes {
                    String A = "a";
                    String B = "b";
                }
                interface Service {
                    String NAME = "service";
                    void run();
                }
                interface Marker {
                }
                """)).containsExactly(1);
    }

    @Test
    void utilityClassConstructor() {
        assertThat(RuleTests.lines(new UtilityClassConstructorRule(), """
                class TextUtils {
                    static String trim(String value) { return value.trim(); }
                }
                class WithConstructor {
                    private WithConstructor() {}
                    static void run() {}
                }
                class Regular {
                    void run() {}
                }
                @UtilityClass
                class Generated {
                    static void run() {}
                }
                class Main {
                    public static void main(String[] args) {}
                }
                """)).containsExactly(1);
    }

    @Test
    void currentTimeMillisForDuration() {
        assertThat(RuleTests.lines(new CurrentTimeMillisForDurationRule(), """
                class Sample {
                    void run() {
                        long start = System.currentTimeMillis();
                        work();
                        long elapsed = System.currentTimeMillis() - start;
                        long nanoStart = System.nanoTime();
                        long nanos = System.nanoTime() - nanoStart;
                    }
                }
                """)).containsExactly(5);
    }

    @Test
    void unusedMock() {
        assertThat(RuleTests.lines(new UnusedMockRule(), """
                class OrdersTest {
                    @Mock
                    private OrderRepository orderRepository;
                    @Mock
                    private Mailer mailer;
                    @Test
                    void places() {
                        when(orderRepository.save(any())).thenReturn(null);
                    }
                }
                class InjectedTest {
                    @Mock
                    private Mailer mailer;
                    @InjectMocks
                    private Orders orders;
                }
                """)).containsExactly(5);
    }

    @Test
    void springBootTestWithoutContext() {
        assertThat(RuleTests.lines(new SpringBootTestWithoutContextRule(), """
                @SpringBootTest
                class MathTest {
                    @Test
                    void adds() { assertEquals(2, 1 + 1); }
                }
                @SpringBootTest
                class ServiceTest {
                    @Autowired
                    private Service service;
                    @Test
                    void works() {}
                }
                @SpringBootTest
                class ApplicationTests {
                    @Test
                    void contextLoads() {}
                }
                """)).containsExactly(1);
    }

    @Test
    void constantAssertion() {
        assertThat(RuleTests.lines(new ConstantAssertionRule(), """
                class SampleTest {
                    @Test
                    void run() {
                        assertTrue(true);
                        assertEquals(5, 5);
                        assertEquals(5, value());
                        assertThat(true).isTrue();
                        assertTrue(result());
                    }
                }
                """)).containsExactly(4, 5, 7);
    }

    // Файл:строка
    private List<String> check(ConfigRule rule) throws IOException {
        return new FileSystemConfigLoader().load(dir).stream()
                .flatMap(file -> rule.checkConfig(file).stream())
                .map(violation -> violation.file().getFileName() + ":" + violation.line())
                .toList();
    }
}
