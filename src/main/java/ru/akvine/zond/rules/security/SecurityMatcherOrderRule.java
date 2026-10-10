package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Порядок правил доступа в Spring Security: применяется первое подходящее, поэтому правило, стоящее ниже
 * более общего, не работает никогда.
 */
@Component
public class SecurityMatcherOrderRule extends AbstractRule {
    private static final Set<String> AUTHORIZE_METHODS = Set.of(
            "authorizeHttpRequests", "authorizeRequests", "authorizeExchange");
    private static final Set<String> MATCHER_METHODS = Set.of(
            "requestMatchers", "antMatchers", "mvcMatchers", "pathMatchers");
    private static final Set<String> ANY_METHODS = Set.of("anyRequest", "anyExchange");
    private static final String HTTP_METHOD = "HttpMethod";
    private static final String EVERYTHING = "/**";
    private static final String SUBTREE = "/**";
    private static final Set<Character> WILDCARDS = Set.of('*', '?', '{');

    /**
     * Одно правило доступа: для каких запросов и что разрешено
     *
     * @param call     вызов, которым заданы запросы: requestMatchers(...) или anyRequest()
     * @param any      правило для всех запросов
     * @param method   HTTP-метод, если правило относится только к нему
     * @param patterns шаблоны адресов; null - заданы не строками, сравнить их нельзя
     * @param access   что разрешено: permitAll(), hasRole("ADMIN")
     */
    private record Matcher(MethodCallExpr call, boolean any, String method, List<String> patterns, String access) {
    }

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
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            boolean configures = method.findAll(MethodCallExpr.class).stream()
                    .anyMatch(call -> AUTHORIZE_METHODS.contains(call.getNameAsString()));
            if (configures) {
                check(sourceFile, matchersOf(method), violations);
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

    // Правила в том порядке, в каком они стоят в коде
    private List<Matcher> matchersOf(MethodDeclaration method) {
        List<Matcher> matchers = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            boolean any = ANY_METHODS.contains(call.getNameAsString());
            if (!any && !MATCHER_METHODS.contains(call.getNameAsString())) {
                continue;
            }
            Optional<MethodCallExpr> access = call.getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr outer
                            && outer.getScope().filter(scope -> scope == call).isPresent())
                    .map(parent -> (MethodCallExpr) parent);
            if (access.isEmpty()) {
                continue;
            }
            String httpMethod = null;
            List<String> patterns = new ArrayList<>();
            for (Expression argument : call.getArguments()) {
                Expression value = Nodes.unwrap(argument);
                Optional<String> text = StringLiterals.textOf(value);
                if (value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().toString().endsWith(HTTP_METHOD)) {
                    httpMethod = value.asFieldAccessExpr().getNameAsString();
                } else if (text.isPresent()) {
                    patterns.add(text.get());
                } else {
                    patterns = null;
                    break;
                }
            }
            matchers.add(new Matcher(call, any, httpMethod, patterns,
                    access.get().getNameAsString() + access.get().getArguments()));
        }
        // Вызов в цепочке начинается там же, где вся цепочка, поэтому порядок задают имена методов
        matchers.sort(Comparator.comparing(matcher -> matcher.call().getName().getBegin().orElseThrow()));
        return matchers;
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
        List<String> wanted = shadowed.patterns().isEmpty() ? List.of(EVERYTHING) : shadowed.patterns();
        return wanted.stream().allMatch(pattern -> general.patterns().stream().anyMatch(wide -> covers(wide, pattern)));
    }

    // /api/** охватывает /api, /api/users и /api/users/**; шаблоны со звездочкой в середине не сравниваем
    private boolean covers(String wide, String pattern) {
        if (wide.equals(pattern) || wide.equals(EVERYTHING)) {
            return true;
        }
        if (!wide.endsWith(SUBTREE)) {
            return false;
        }
        String prefix = wide.substring(0, wide.length() - SUBTREE.length());
        boolean plain = prefix.chars().noneMatch(symbol -> WILDCARDS.contains((char) symbol));
        return plain && (pattern.equals(prefix) || pattern.startsWith(prefix + "/"));
    }

    private String describe(Matcher matcher) {
        return matcher.call().getNameAsString() + matcher.call().getArguments().toString().replace("[", "(").replace("]", ")")
                + "." + matcher.access().replace("[", "(").replace("]", ")");
    }
}
