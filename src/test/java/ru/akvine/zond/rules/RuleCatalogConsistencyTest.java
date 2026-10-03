package ru.akvine.zond.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сверка всех правил приложения разом: ошибку в одном из двух сотен классов (повтор кода, забытое описание,
 * пропущенный @Component) иначе легко не заметить.
 */
class RuleCatalogConsistencyTest {
    private static final String RULES_PACKAGE = "ru.akvine.zond.rules";
    private static final Pattern CODE = Pattern.compile("jr:[1-9]\\d*");
    private static final Pattern PARAMETER_NAME = Pattern.compile("[a-z]+(-[a-z]+)*");

    // Описание из пары слов ничего не объясняет в списке правил
    private static final int MIN_DESCRIPTION_LENGTH = 15;

    private static List<Rule> rules;

    @BeforeAll
    static void findRules() throws ReflectiveOperationException {
        // Все неабстрактные классы правил, а не только бины: так видно и правило, на котором забыли @Component
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Rule.class));

        rules = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(RULES_PACKAGE)) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            rules.add((Rule) type.getDeclaredConstructor().newInstance());
        }
        rules.sort(Comparator.comparing(Rule::name));
    }

    @Test
    void rulesAreFound() {
        assertThat(rules).hasSizeGreaterThan(200);
    }

    @Test
    void codesAreUnique() {
        assertThat(duplicates(Rule::code)).as("коды, которые заняты несколькими правилами").isEmpty();
    }

    @Test
    void codesHaveExpectedFormat() {
        assertThat(rules)
                .as("правила с кодом не вида jr:<номер>")
                .allSatisfy(rule -> assertThat(rule.code()).as(rule.name()).matches(CODE));
    }

    @Test
    void everyCodeConstantBelongsToExactlyOneRule() throws IllegalAccessException {
        Set<String> declared = new HashSet<>();
        for (Field field : RuleCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                // Две константы с одним значением - тоже повтор кода, только еще не дошедший до правил
                assertThat(declared.add((String) field.get(null))).as("повтор значения в " + field.getName()).isTrue();
            }
        }

        Set<String> used = rules.stream().map(Rule::code).collect(Collectors.toSet());
        assertThat(used).as("коды правил и константы RuleCodes").containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void namesAreUnique() {
        assertThat(duplicates(Rule::name)).as("имена, которые носят несколько правил").isEmpty();
    }

    @Test
    void everyRuleHasDescription() {
        assertThat(rules).allSatisfy(rule -> assertThat(rule.description())
                .as("описание правила " + rule.name())
                .isNotBlank()
                .hasSizeGreaterThanOrEqualTo(MIN_DESCRIPTION_LENGTH));
    }

    @Test
    void descriptionsAreUnique() {
        assertThat(duplicates(Rule::description)).as("описания, скопированные из другого правила").isEmpty();
    }

    @Test
    void everyRuleHasLevelAndType() {
        assertThat(rules).allSatisfy(rule -> {
            assertThat(rule.errorLevel()).as("уровень правила " + rule.name()).isNotNull();
            assertThat(rule.errorType()).as("тип правила " + rule.name()).isNotNull().isNotEqualTo(ErrorType.UNKNOWN);
        });
    }

    @Test
    void everyRuleIsSpringBean() {
        // Без @Component правило компилируется и проходит свои тесты, но при сканировании не запускается
        assertThat(rules)
                .filteredOn(rule -> !rule.getClass().isAnnotationPresent(Component.class))
                .extracting(Rule::name)
                .as("правила без @Component")
                .isEmpty();
    }

    @Test
    void parametersAreWellFormed() {
        for (Rule rule : rules) {
            List<RuleParameter> parameters = rule.parameters();
            assertThat(parameters.stream().map(RuleParameter::name))
                    .as("параметры правила " + rule.name())
                    .doesNotHaveDuplicates()
                    // level занят переопределением уровня правила
                    .doesNotContain(RuleSettings.LEVEL)
                    .allMatch(name -> PARAMETER_NAME.matcher(name).matches());
            assertThat(parameters).allSatisfy(parameter -> {
                assertThat(parameter.description()).as(rule.name() + "." + parameter.name()).isNotBlank();
                assertThat(parameter.defaultValue()).as(rule.name() + "." + parameter.name()).isNotNegative();
            });
        }
    }

    // Значения, которые встречаются больше чем у одного правила, вместе с именами этих правил
    private List<String> duplicates(Function<Rule, String> property) {
        return rules.stream()
                .collect(Collectors.groupingBy(property, Collectors.mapping(Rule::name, Collectors.toList())))
                .entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " -> " + entry.getValue())
                .sorted()
                .toList();
    }
}
