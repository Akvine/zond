package ru.akvine.zond.rules.support;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Запрос, который Spring Data строит по имени метода репозитория: findByEmailAndTenantId ищет
 * по свойствам email и tenantId
 */
@UtilityClass
public class DerivedQueries {
    private static final Pattern METHOD = Pattern.compile(
            "^(find|read|get|query|search|stream|count|exists|delete|remove)(?:\\p{Upper}\\w*?)?By(\\p{Upper}.*)$");
    private static final Set<String> EXISTENCE_ACTIONS = Set.of("exists", "count");
    private static final Pattern ORDER_BY = Pattern.compile("OrderBy\\p{Upper}.*$");
    private static final Pattern OR = Pattern.compile("(?<=[a-z0-9])Or(?=\\p{Upper})");
    private static final Pattern AND_OR = Pattern.compile("(?<=[a-z0-9])(?:And|Or)(?=\\p{Upper})");
    private static final Pattern IGNORE_CASE = Pattern.compile("(AllIgnoreCase|AllIgnoringCase|IgnoreCase|IgnoringCase)$");
    private static final String ID_SUFFIX = "Id";

    // Слово в конце условия -> вид сравнения. Порядок важен: длинное слово проверяется раньше короткого,
    // которым оно заканчивается (NotIn раньше In, LessThanEqual раньше Equal)
    private static final List<Map.Entry<String, Operator>> SUFFIXES = List.of(
            Map.entry("IsNotNull", Operator.OTHER), Map.entry("NotNull", Operator.OTHER),
            Map.entry("IsNull", Operator.OTHER), Map.entry("Null", Operator.OTHER),
            Map.entry("IsNotEmpty", Operator.OTHER), Map.entry("NotEmpty", Operator.OTHER),
            Map.entry("IsEmpty", Operator.OTHER), Map.entry("Empty", Operator.OTHER),
            Map.entry("IsNotIn", Operator.OTHER), Map.entry("NotIn", Operator.OTHER),
            Map.entry("IsIn", Operator.IN), Map.entry("In", Operator.IN),
            Map.entry("IsNotLike", Operator.OTHER), Map.entry("NotLike", Operator.OTHER),
            Map.entry("IsLike", Operator.OTHER), Map.entry("Like", Operator.OTHER),
            Map.entry("NotContaining", Operator.OTHER), Map.entry("IsContaining", Operator.OTHER),
            Map.entry("Containing", Operator.OTHER), Map.entry("Contains", Operator.OTHER),
            Map.entry("IsEndingWith", Operator.OTHER), Map.entry("EndingWith", Operator.OTHER),
            Map.entry("EndsWith", Operator.OTHER),
            Map.entry("IsStartingWith", Operator.PREFIX), Map.entry("StartingWith", Operator.PREFIX),
            Map.entry("StartsWith", Operator.PREFIX),
            Map.entry("IsBetween", Operator.RANGE), Map.entry("Between", Operator.RANGE),
            Map.entry("IsLessThanEqual", Operator.RANGE), Map.entry("LessThanEqual", Operator.RANGE),
            Map.entry("IsLessThan", Operator.RANGE), Map.entry("LessThan", Operator.RANGE),
            Map.entry("IsGreaterThanEqual", Operator.RANGE), Map.entry("GreaterThanEqual", Operator.RANGE),
            Map.entry("IsGreaterThan", Operator.RANGE), Map.entry("GreaterThan", Operator.RANGE),
            Map.entry("IsBefore", Operator.RANGE), Map.entry("Before", Operator.RANGE),
            Map.entry("IsAfter", Operator.RANGE), Map.entry("After", Operator.RANGE),
            Map.entry("IsTrue", Operator.OTHER), Map.entry("True", Operator.OTHER),
            Map.entry("IsFalse", Operator.OTHER), Map.entry("False", Operator.OTHER),
            Map.entry("MatchesRegex", Operator.OTHER), Map.entry("Regex", Operator.OTHER),
            Map.entry("IsNot", Operator.OTHER), Map.entry("Not", Operator.OTHER),
            Map.entry("Equals", Operator.EQUALS), Map.entry("Is", Operator.EQUALS));

    /**
     * Как свойство сравнивается со значением
     */
    public enum Operator {
        /** Точное совпадение: findByEmail */
        EQUALS,
        /** Совпадение с одним из значений: findByStatusIn */
        IN,
        /** Диапазон: Between, LessThan, GreaterThan, Before, After */
        RANGE,
        /** Начало строки: StartingWith */
        PREFIX,
        /** Все остальное: Like, Containing, Not, IsNull, True - индекс по колонке такому условию не помощник */
        OTHER
    }

    /**
     * @param property   свойство сущности: email, tenantId
     * @param ignoreCase сравнение без учета регистра
     */
    public record Part(String property, Operator operator, boolean ignoreCase) {
    }

    /**
     * @param action       что делает метод: find, exists, count, delete
     * @param parts        условия в том порядке, в каком они названы
     * @param alternatives условия соединены через Or: подходит запись, для которой выполнено любое из них
     */
    public record Query(String action, List<Part> parts, boolean alternatives) {

        /**
         * @return true, если метод только проверяет, есть ли такие записи: existsBy..., countBy...
         */
        public boolean isExistenceCheck() {
            return EXISTENCE_ACTIONS.contains(action);
        }

        /**
         * @return свойства, если запрос - "все эти свойства равны заданным значениям"; пусто для любого другого
         */
        public Optional<List<String>> equalityProperties() {
            boolean exact = !alternatives && parts.stream().allMatch(part -> part.operator() == Operator.EQUALS);
            return exact ? Optional.of(parts.stream().map(Part::property).toList()) : Optional.empty();
        }
    }

    /**
     * @return запрос, если имя метода построено по правилам Spring Data; пусто для findAll(), save() и своих имен
     */
    public Optional<Query> parse(String methodName) {
        Matcher method = METHOD.matcher(methodName);
        if (!method.matches()) {
            return Optional.empty();
        }
        String condition = ORDER_BY.matcher(method.group(2)).replaceFirst("");
        List<Part> parts = new ArrayList<>();
        for (String text : AND_OR.split(condition)) {
            if (!text.isEmpty()) {
                parts.add(partOf(text));
            }
        }
        return parts.isEmpty()
                ? Optional.empty()
                : Optional.of(new Query(method.group(1), parts, OR.matcher(condition).find()));
    }

    /**
     * Поле сущности, которому соответствует свойство из имени метода. customerId может быть и своим полем,
     * и ключом связи customer.
     */
    public Optional<EntityMapping.MappedField> fieldOf(List<EntityMapping.MappedField> fields, String property) {
        Optional<EntityMapping.MappedField> own = fields.stream()
                .filter(field -> field.variable().getNameAsString().equals(property))
                .findFirst();
        if (own.isPresent() || !property.endsWith(ID_SUFFIX)) {
            return own;
        }
        String relation = property.substring(0, property.length() - ID_SUFFIX.length());
        return fields.stream().filter(field -> field.variable().getNameAsString().equals(relation)).findFirst();
    }

    private Part partOf(String text) {
        String rest = IGNORE_CASE.matcher(text).replaceFirst("");
        boolean ignoreCase = rest.length() != text.length();
        for (Map.Entry<String, Operator> suffix : SUFFIXES) {
            // Слово должно стоять после имени свойства: само свойство может называться Null или In
            if (rest.length() > suffix.getKey().length() && rest.endsWith(suffix.getKey())) {
                String property = rest.substring(0, rest.length() - suffix.getKey().length());
                return new Part(lowerFirst(property), suffix.getValue(), ignoreCase);
            }
        }
        return new Part(lowerFirst(rest), Operator.EQUALS, ignoreCase);
    }

    private String lowerFirst(String name) {
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
