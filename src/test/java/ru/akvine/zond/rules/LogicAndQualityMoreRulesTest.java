package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:279 - jr:289: логические ошибки и качество кода
 */
class LogicAndQualityMoreRulesTest {

    @Test
    void nullCheckWrongOperator() {
        assertThat(RuleTests.lines(new CheckNullCheckWrongOperatorRule(), """
                class Sample {
                    void run(String s, String t) {
                        if (s == null && s.isEmpty()) {}
                        if (s != null || s.length() > 0) {}
                        if (s == null || s.isEmpty()) {}
                        if (s != null && s.isEmpty()) {}
                        if (s == null && t.isEmpty()) {}
                        if (t != null && s == null && s.isEmpty()) {}
                    }
                }
                """)).containsExactly(3, 4, 8);
    }

    @Test
    void incompatibleTypes() {
        List<Violation> found = RuleTests.check(new CheckIncompatibleTypesRule(), """
                class Sample {
                    void run(List<Long> ids, Map<String, User> users, List<Integer> numbers, Set<String> names,
                             String name, Long id, Integer count, int index) {
                        ids.contains(1);
                        ids.contains(1L);
                        users.get(id);
                        users.get(name);
                        name.equals(count);
                        id.equals(count);
                        name.equals("x");
                        numbers.remove(index);
                        ids.remove(index);
                        names.remove(name);
                        names.contains(id);
                    }
                }
                """);

        assertThat(found).extracting(Violation::line).containsExactly(4, 6, 8, 9, 11, 14);
        assertThat(found.get(0).message()).contains("сравниваются Long и Integer");
        assertThat(found.get(4).message()).contains("remove(int) удаляет элемент по индексу");
    }

    @Test
    void exceptionNotThrown() {
        assertThat(RuleTests.lines(new CheckExceptionNotThrownRule(), """
                class Sample {
                    void run(int x) {
                        if (x < 0) {
                            new IllegalArgumentException("negative");
                        }
                        throw new IllegalStateException("x");
                    }
                    void other() {
                        Exception saved = new RuntimeException("x");
                        new Thread(this::run).start();
                        find().orElseThrow(() -> new IllegalStateException("missing"));
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void formatArgumentsMismatch() {
        assertThat(RuleTests.lines(new CheckFormatArgumentsMismatchRule(), """
                class Sample {
                    private static final String ROW = "%s | %s";
                    void run(String name, int age, Object[] values) {
                        String.format("%s is %d", name);
                        String.format("%s is %d", name, age);
                        String.format("%s", name, age);
                        String.format("100%% of %s%n", name);
                        String.format(Locale.ROOT, "%s is %d", name);
                        String.format("%2$s %1$s", name, age);
                        String.format("%s %s", values);
                        "%s and %s".formatted(name);
                        String.format(ROW, name, age);
                        String.format(ROW, name);
                        String.format(EXTERNAL_FORMAT, name, age, values);
                    }
                }
                """)).containsExactly(4, 6, 8, 11, 13);
    }

    @Test
    void duplicateCondition() {
        assertThat(RuleTests.lines(new CheckDuplicateConditionRule(), """
                class Sample {
                    void run(int x) {
                        if (x > 0) {
                            a();
                        } else if (x < 0) {
                            b();
                        } else if (x > 0) {
                            c();
                        }
                        if (x > 0) { a(); }
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void indexOfPositive() {
        assertThat(RuleTests.lines(new CheckIndexOfPositiveRule(), """
                class Sample {
                    void run(String text) {
                        if (text.indexOf("a") > 0) {}
                        if (text.indexOf("a") >= 0) {}
                        if (0 < text.indexOf("a")) {}
                        if (text.indexOf("a") > 1) {}
                    }
                }
                """)).containsExactly(3, 5);
    }

    @Test
    void nonShortCircuitLogic() {
        assertThat(RuleTests.lines(new CheckNonShortCircuitLogicRule(), """
                class Sample {
                    void run(String s, int flags, boolean a, boolean b) {
                        if (s != null & s.isEmpty()) {}
                        if (a | b) {}
                        int masked = flags & 0xFF;
                        if (a && b) {}
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void narrowingCast() {
        assertThat(RuleTests.lines(new CheckNarrowingCastRule(), """
                class Sample {
                    void run(long total, int small) {
                        int a = (int) total;
                        int b = (int) (total % 100);
                        long c = (long) small;
                        int d = (int) small;
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void mutablePublicConstant() {
        assertThat(RuleTests.lines(new CheckMutablePublicConstantRule(), """
                class Constants {
                    public static final List<String> NAMES = new ArrayList<>();
                    public static final String[] CODES = {"a", "b"};
                    public static final List<String> FIXED = List.of("a");
                    public static final String[] EMPTY = new String[0];
                    public static final List<String> VIEW = Arrays.asList("a", "b");
                    private static final List<String> HIDDEN = new ArrayList<>();
                    public static final String NAME = "x";
                }
                interface Codes {
                    String[] ALL = {"a"};
                }
                """)).containsExactly(2, 3, 6, 11);
    }

    @Test
    void sizeComparedToZero() {
        assertThat(RuleTests.lines(new CheckSizeComparedToZeroRule(), """
                class Sample {
                    void run(List<String> list, String text, StringBuilder builder) {
                        if (list.size() == 0) {}
                        if (list.size() > 0) {}
                        if (0 == list.size()) {}
                        if (text.length() == 0) {}
                        if (builder.length() == 0) {}
                        if (list.size() > 1) {}
                        if (list.isEmpty()) {}
                    }
                    public boolean isEmpty() {
                        return items.size() == 0;
                    }
                }
                """)).containsExactly(3, 4, 5, 6);
    }

    @Test
    void rawType() {
        assertThat(RuleTests.lines(new CheckRawTypeRule(), """
                class Sample {
                    private List names = new ArrayList();
                    private List<String> typed = new ArrayList<>();
                    Map index(Collection values) { return null; }
                    void run(Object value) {
                        boolean isList = value instanceof List;
                        Class<?> type = List.class;
                        Map.Entry<String, String> entry = null;
                        List<Map> nested = new ArrayList<>();
                    }
                }
                """)).containsExactly(2, 4, 9);
    }
}
