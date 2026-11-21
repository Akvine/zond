package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Правила доступа Spring Security так, как они записаны в настройке цепочки фильтров:
 * {@code requestMatchers("/api/**").hasRole("USER")}, {@code anyRequest().authenticated()}
 */
@UtilityClass
public class SecurityMatchers {
    private final static Set<String> AUTHORIZE_METHODS = Set.of(
            "authorizeHttpRequests", "authorizeRequests", "authorizeExchange");
    private final static Set<String> MATCHER_METHODS = Set.of(
            "requestMatchers", "antMatchers", "mvcMatchers", "pathMatchers");
    private final static Set<String> ANY_METHODS = Set.of("anyRequest", "anyExchange");
    private final static String HTTP_METHOD = "HttpMethod";
    public final static String EVERYTHING = "/**";
    private final static String SUBTREE = "/**";
    private final static Set<Character> WILDCARDS = Set.of('*', '?', '{');
    private final static String PERMIT_ALL = "permitAll";
    private final static String IGNORING = "ignoring";
    private final static Set<String> AUTHENTICATED = Set.of("authenticated", "fullyAuthenticated");

    // Переменная в адресе обработчика: /bot/{secret}, /files/{name:.+}
    private final static Pattern PATH_VARIABLE = Pattern.compile("\\{[^/}]*}");
    private final static String SEGMENT = "x";

    /**
     * Одно правило доступа: для каких запросов и что разрешено
     *
     * @param call     вызов, которым заданы запросы: requestMatchers(...) или anyRequest()
     * @param any      правило для всех запросов
     * @param method   HTTP-метод, если правило относится только к нему
     * @param patterns шаблоны адресов; null - заданы не строками, сравнить их нельзя
     * @param access   что разрешено: permitAll(), hasRole("ADMIN")
     */
    public record Matcher(MethodCallExpr call, boolean any, String method, List<String> patterns, String access) {

        public boolean permitsAll() {
            return access.startsWith(PERMIT_ALL);
        }

        /**
         * @return true, если правило пускает только вошедших: authenticated() без ролей и условий
         */
        public boolean requiresLogin() {
            return AUTHENTICATED.stream().anyMatch(access::startsWith);
        }
    }

    /**
     * @return true для метода, который настраивает доступ к адресам
     */
    public boolean configures(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> AUTHORIZE_METHODS.contains(call.getNameAsString()));
    }

    /**
     * @return правила в том порядке, в каком они стоят в коде
     */
    public List<Matcher> of(MethodDeclaration method) {
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
                if (value.isFieldAccessExpr()
                        && value.asFieldAccessExpr().getScope().toString().endsWith(HTTP_METHOD)) {
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

    /**
     * @return шаблоны адресов, которые выведены из-под Spring Security целиком: web.ignoring().requestMatchers(...);
     * пусто, если хотя бы один из них задан не строкой и сравнить его нельзя
     */
    public Optional<List<String>> ignored(Node root) {
        List<String> patterns = new ArrayList<>();
        for (MethodCallExpr call : root.findAll(MethodCallExpr.class)) {
            if (!MATCHER_METHODS.contains(call.getNameAsString()) || !isAfterIgnoring(call)) {
                continue;
            }
            for (Expression argument : call.getArguments()) {
                Expression value = Nodes.unwrap(argument);
                Optional<String> text = StringLiterals.textOf(value);
                boolean httpMethod = value.isFieldAccessExpr()
                        && value.asFieldAccessExpr().getScope().toString().endsWith(HTTP_METHOD);
                if (text.isEmpty() && !httpMethod) {
                    return Optional.empty();
                }
                text.ifPresent(patterns::add);
            }
        }
        return Optional.of(patterns);
    }

    // web.ignoring().requestMatchers("/a").requestMatchers("/b"): ignoring() стоит раньше в той же цепочке
    private boolean isAfterIgnoring(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        while (scope.isPresent() && Nodes.unwrap(scope.get()).isMethodCallExpr()) {
            MethodCallExpr previous = Nodes.unwrap(scope.get()).asMethodCallExpr();
            if (IGNORING.equals(previous.getNameAsString())) {
                return true;
            }
            scope = previous.getScope();
        }
        return false;
    }

    /**
     * @return true, если шаблон wide охватывает все адреса шаблона pattern: /api/** охватывает /api, /api/users
     * и /api/users/**. Шаблоны со звездочкой в середине не сравниваются
     */
    public boolean covers(String wide, String pattern) {
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

    /**
     * @param pattern шаблон правила доступа: /api/**, /files/*, /bot/{secret}
     * @param path    адрес обработчика; переменные в нем ({id}) считаются одной частью пути
     * @return true, если запрос на этот адрес подпадает под шаблон
     */
    public boolean matches(String pattern, String path) {
        String sample = PATH_VARIABLE.matcher(path).replaceAll(SEGMENT);
        String glob = PATH_VARIABLE.matcher(pattern).replaceAll("*");
        boolean subtree = glob.endsWith(SUBTREE);
        String body = subtree ? glob.substring(0, glob.length() - SUBTREE.length()) : glob;
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < body.length(); index++) {
            char symbol = body.charAt(index);
            if (symbol == '*' && body.startsWith("**", index)) {
                regex.append(".*");
                index++;
            } else if (symbol == '*') {
                regex.append("[^/]*");
            } else if (symbol == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(symbol)));
            }
        }
        // /api/** в конце шаблона подходит и самому /api
        if (subtree) {
            regex.append("(/.*)?");
        }
        return Pattern.matches(regex.toString(), sample);
    }
}
