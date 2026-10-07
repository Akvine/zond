package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.AssertEqualsArgumentOrderRule;
import ru.akvine.zond.rules.codesmell.UncheckedResultInTestRule;
import ru.akvine.zond.rules.logical.AssertionOnlyInCatchRule;
import ru.akvine.zond.rules.logical.TestMethodNotRunRule;
import ru.akvine.zond.rules.logical.TestSharedStaticStateRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:344 - jr:348: ошибки в самих тестах
 */
class TestQualityRulesTest {

    @Test
    void expectedValueGoesFirst() {
        assertThat(RuleTests.lines(new AssertEqualsArgumentOrderRule(), """
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class OrderTest {
                    @Test
                    void totals() {
                        assertEquals(order.total(), 100);
                        assertEquals(100, order.total());
                        assertEquals(order.status(), Status.NEW, "статус");
                        assertEquals(order.code(), EXPECTED_CODE);
                        assertEquals(first.total(), second.total());
                        assertEquals(1, 1);
                        assertEquals(-1, order.shift());
                        assertNotEquals(order.total(), 0);
                    }
                }
                """)).containsExactly(5, 7, 8, 12);
    }

    @Test
    void junit4MessageGoesFirstAndTestNgOrderIsReversed() {
        assertThat(RuleTests.lines(new AssertEqualsArgumentOrderRule(), """
                import org.junit.Assert;
                class OrderTest {
                    @Test
                    public void totals() {
                        Assert.assertEquals("сумма", 100, order.total());
                        Assert.assertEquals("сумма", order.total(), 100);
                    }
                }
                """)).containsExactly(6);
        assertThat(RuleTests.lines(new AssertEqualsArgumentOrderRule(), """
                import static org.testng.Assert.assertEquals;
                class OrderTest {
                    @Test
                    public void totals() {
                        assertEquals(order.total(), 100);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void assertionsInCatchNeedFail() {
        assertThat(RuleTests.lines(new AssertionOnlyInCatchRule(), """
                class OrderTest {
                    @Test
                    void rejectsNegativeTotal() {
                        try {
                            service.create(-1);
                        } catch (IllegalArgumentException exception) {
                            assertEquals("сумма", exception.getMessage());
                        }
                    }
                    @Test
                    void rejectsWithFail() {
                        try {
                            service.create(-1);
                            fail("ждали исключение");
                        } catch (IllegalArgumentException exception) {
                            assertEquals("сумма", exception.getMessage());
                        }
                    }
                    @Test
                    void logsOnly() {
                        try {
                            service.create(1);
                        } catch (IllegalStateException exception) {
                            log.warn("не удалось", exception);
                        }
                    }
                    void helper() {
                        try {
                            service.create(-1);
                        } catch (IllegalArgumentException exception) {
                            assertEquals("сумма", exception.getMessage());
                        }
                    }
                }
                """)).containsExactly(6);
    }

    @Test
    void resultShouldBeCheckedNotOnlyMocks() {
        assertThat(RuleTests.lines(new UncheckedResultInTestRule(), """
                class OrderTest {
                    @Test
                    void savesOrder() {
                        Order saved = service.create(request);
                        verify(repository).save(any());
                    }
                    @Test
                    void savesAndChecks() {
                        Order saved = service.create(request);
                        verify(repository).save(any());
                        assertEquals(1, saved.getId());
                    }
                    @Test
                    void usesResult() {
                        Order saved = service.create(request);
                        verify(repository).save(saved);
                    }
                    @Test
                    void voidCall() {
                        Order prepared = mock(Order.class);
                        service.delete(1);
                        verify(repository).deleteById(1);
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void testMethodMustBeRunnable() {
        List<Violation> violations = RuleTests.check(new TestMethodNotRunRule(), """
                class OrderTest {
                    @Test
                    void createsOrder() {}
                    @Test
                    private void hidden() {}
                    @Test
                    static void shared() {}
                    void testWithoutAnnotation() {}
                    void shouldAlsoRun() {}
                    void testHelper() {}
                    void prepare() {}
                    void testWithArgument(int value) {}
                    @BeforeEach
                    void testSetup() {}
                    @Test
                    void usesHelper() { testHelper(); }
                }
                """);

        // testHelper вызывается из теста - это вспомогательный метод, а не забытый тест
        assertThat(violations).extracting(Violation::line).containsExactly(4, 6, 8, 9);
        assertThat(violations).extracting(Violation::confidence)
                .containsExactly(Confidence.CONFIRMED, Confidence.CONFIRMED, null, null);
    }

    @Test
    void junit3TestsNeedNoAnnotation() {
        assertThat(RuleTests.lines(new TestMethodNotRunRule(), """
                class OrderTest extends TestCase {
                    @Test
                    public void modern() {}
                    public void testLegacy() {}
                }
                """)).isEmpty();
    }

    @Test
    void testsMustNotShareStaticState() {
        assertThat(RuleTests.lines(new TestSharedStaticStateRule(), """
                class OrderTest {
                    private static int counter;
                    private static final List<String> EVENTS = new ArrayList<>();
                    private static final List<String> CLEANED = new ArrayList<>();
                    private static String server;
                    private static final int LIMIT = 10;
                    @Container
                    private static PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16");
                    @BeforeAll
                    static void start() { server = "http://localhost"; }
                    @BeforeEach
                    void reset() { CLEANED.clear(); }
                    @Test
                    void first() {
                        counter++;
                        EVENTS.add("first");
                        CLEANED.add("first");
                        database = null;
                    }
                    @Test
                    void second() {
                        int counter = 0;
                        counter++;
                        assertEquals(LIMIT, EVENTS.size());
                    }
                }
                """)).containsExactly(15, 16);
    }

    @Test
    void orderedTestsMayShareState() {
        assertThat(RuleTests.lines(new TestSharedStaticStateRule(), """
                @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
                class OrderTest {
                    private static Long createdId;
                    @Test
                    @Order(1)
                    void creates() { createdId = 1L; }
                }
                """)).isEmpty();
    }
}
