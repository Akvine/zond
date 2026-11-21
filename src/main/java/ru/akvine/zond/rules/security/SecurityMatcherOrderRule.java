package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.SecurityMatchers;
import ru.akvine.zond.rules.support.SecurityMatchers.Matcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Порядок правил доступа в Spring Security: применяется первое подходящее, поэтому правило, стоящее ниже
 * более общего, не работает никогда.
 */
@Component
public class SecurityMatcherOrderRule extends AbstractRule {
    @Override
    public String code() {
        return RuleCodes.SECURITY_MATCHER_ORDER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует настройку Spring Security и ищет правила доступа, перекрытые более общим правилом выше";
    }

    // Перекрытие видно в коде целиком: догадок здесь нет
    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
            if (SecurityMatchers.configures(method)) {
                check(sourceFile, SecurityMatchers.of(method), violations);
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private void check(SourceFile sourceFile, List<Matcher> matchers, List<Violation> violations) {
        for (int later = 1; later < matchers.size(); later++) {
            Matcher shadowed = matchers.get(later);
            for (int earlier = 0; earlier < later; earlier++) {
                Matcher general = matchers.get(earlier);
                // Одинаковый доступ: нижнее правило лишнее, но ничего не меняет
                if (general.access().equals(shadowed.access()) || !covers(general, shadowed)) {
                    continue;
                }
                violations.add(violation(sourceFile, shadowed.call().getName(),
                        "Правило '" + describe(shadowed) + "' не сработает никогда: выше стоит '"
                                + describe(general) + "', которое охватывает те же запросы, а Spring Security"
                                + " применяет первое подходящее правило - доступ будет таким, как задано выше;"
                                + " поставьте частные правила перед общими"));
                break;
            }
        }
    }

    private boolean covers(Matcher general, Matcher shadowed) {
        if (general.any()) {
            return true;
        }
        if (shadowed.any() || general.patterns() == null || shadowed.patterns() == null) {
            return false;
        }
        // Правило для одного HTTP-метода не охватывает ни другой метод, ни правило для всех методов сразу
        if (general.method() != null && !general.method().equals(shadowed.method())) {
            return false;
        }
        // requestMatchers(HttpMethod.GET) без адресов относится ко всем адресам
        if (general.patterns().isEmpty()) {
            return true;
        }
        List<String> wanted = shadowed.patterns().isEmpty()
                ? List.of(SecurityMatchers.EVERYTHING)
                : shadowed.patterns();
        return wanted.stream().allMatch(pattern -> general.patterns().stream()
                .anyMatch(wide -> SecurityMatchers.covers(wide, pattern)));
    }

    private String describe(Matcher matcher) {
        return matcher.call().getNameAsString() + matcher.call().getArguments().toString().replace("[", "(").replace("]", ")")
                + "." + matcher.access().replace("[", "(").replace("]", ")");
    }
}
