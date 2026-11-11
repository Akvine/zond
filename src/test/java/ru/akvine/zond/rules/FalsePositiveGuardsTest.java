package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.exceptions.UnhandledNumberFormatRule;
import ru.akvine.zond.rules.logical.EqualsOnPossibleNullRule;
import ru.akvine.zond.rules.logical.IndexOutOfBoundsRule;
import ru.akvine.zond.rules.logical.IndexWithoutLengthCheckRule;
import ru.akvine.zond.rules.logical.NarrowingCastRule;
import ru.akvine.zond.rules.logical.NullableDereferenceRule;
import ru.akvine.zond.rules.logical.OptionalMisuseRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные прогоном Zond по собственному коду: значение уже проверено, но проверка записана
 * не рядом с использованием - через производную переменную, флаг, шаблон или операцию выше по цепочке
 */
class FalsePositiveGuardsTest {

    @Test
    void optionalIsCheckedThroughDerivedValue() {
        assertThat(RuleTests.lines(new OptionalMisuseRule(), """
                class Finder {
                    String city(Optional<User> user) {
                        Optional<Address> address = user.map(User::address);
                        if (address.isPresent()) {
                            return user.get().name() + address.get().city();
                        }
                        return "";
                    }
                    String active(Optional<User> user) {
                        return user.filter(found -> user.get().isActive()).map(User::name).orElse("");
                    }
                    String unchecked(Optional<User> user) {
                        Optional<Address> address = user.map(User::address);
                        return user.get().name() + address.get().city();
                    }
                }
                """)).containsExactly(14, 14);
    }

    @Test
    void searchResultIsCheckedByFlagFilterOrLeftOperand() {
        assertThat(RuleTests.lines(new IndexOutOfBoundsRule(), """
                class Parser {
                    String conditions(String predicate) {
                        int orderBy = predicate.indexOf("OrderBy");
                        boolean sorted = orderBy >= 0 && predicate.length() > orderBy + 7;
                        return sorted ? predicate.substring(0, orderBy) : predicate;
                    }
                    List<String> keys(List<String> lines) {
                        return lines.stream()
                                .filter(line -> !line.startsWith("#") && line.contains("="))
                                .map(line -> line.substring(0, line.indexOf('=')))
                                .toList();
                    }
                    boolean hasValue(String text) {
                        return text.contains(":") && !text.substring(text.indexOf(':')).isBlank();
                    }
                    String unchecked(String line) {
                        return line.substring(0, line.indexOf('='));
                    }
                    List<String> unfiltered(List<String> lines) {
                        return lines.stream().map(line -> line.substring(0, line.indexOf('='))).toList();
                    }
                }
                """)).containsExactly(17, 20);
    }

    @Test
    void numberIsValidatedBeforeParsing() {
        assertThat(RuleTests.lines(new UnhandledNumberFormatRule(), """
                class Settings {
                    private static final Pattern NUMBER = Pattern.compile("\\\\d+");
                    private static final Pattern SIZE = Pattern.compile("(\\\\d+)(mb|gb)");
                    int threads(String value) {
                        if (!NUMBER.matcher(value).matches()) {
                            return 1;
                        }
                        return Integer.parseInt(value);
                    }
                    int limit(String value) {
                        return value.matches("\\\\d+") ? Integer.parseInt(value) : 0;
                    }
                    long size(String text) {
                        Matcher matcher = SIZE.matcher(text);
                        if (!matcher.matches()) {
                            return 0;
                        }
                        String amount = matcher.group(1);
                        return Long.parseLong(amount) + Long.parseLong(matcher.group(1));
                    }
                    int port(String value) {
                        return Integer.parseInt(value);
                    }
                }
                """)).containsExactly(22);
    }

    @Test
    void sameStringIsReportedOncePerStatement() {
        assertThat(RuleTests.lines(new IndexWithoutLengthCheckRule(), """
                class Names {
                    String capitalize(String name) {
                        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    }
                    String head(String text, Matcher matcher) {
                        return text.substring(0, matcher.start());
                    }
                    String prefix(String text) {
                        char first = text.charAt(0);
                        return first + text.substring(0, 3);
                    }
                }
                """)).containsExactly(3, 9, 10);
    }

    @Test
    void nameOfMethodIsNeverNull() {
        assertThat(RuleTests.lines(new EqualsOnPossibleNullRule(), """
                class Handler {
                    Object invoke(Method method, Order order) {
                        if (method.getName().equals("close")) {
                            return null;
                        }
                        return order.getStatus().equals("NEW") ? order : null;
                    }
                }
                """)).containsExactly(6);
    }

    @Test
    void valueComparedWithIntBoundFitsInt() {
        assertThat(RuleTests.lines(new NarrowingCastRule(), """
                class Sizes {
                    int length(long length, int maxSize) {
                        return length > maxSize ? maxSize : (int) length;
                    }
                    int positive(long count) {
                        return count > 0 ? (int) count : 0;
                    }
                }
                """)).containsExactly(6);
    }

    @Test
    void elementByNumberIsNotRequestParameter() {
        assertThat(RuleTests.lines(new NullableDereferenceRule(), """
                class Handlers {
                    String type(MethodDeclaration method, int index) {
                        Parameter first = method.getParameter(0);
                        Parameter other = method.getParameter(index);
                        return first.getNameAsString() + other.getNameAsString();
                    }
                    String page(HttpServletRequest request) {
                        String page = request.getParameter("page");
                        return page.trim();
                    }
                }
                """)).containsExactly(9);
    }
}
