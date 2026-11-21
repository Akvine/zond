package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallChains;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.Handlers;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Mappings;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Secrets;
import ru.akvine.zond.rules.support.SecurityMatchers;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Обработчик, к которому по его смыслу приходят без входа в систему, закрыт правилом "только для вошедших":
 * <ul>
 *     <li>вход и регистрация - обработчик сам кладет пользователя в контекст безопасности, но вызвать его может
 *     лишь тот, кто уже вошел;</li>
 *     <li>вебхук - внешняя система сессии не имеет и предъявляет секрет, который обработчик проверяет сам.</li>
 * </ul>
 * Неавторизованный запрос до такого обработчика не доходит: Spring Security отвечает 401 или 403 раньше.
 * <p>
 * Судим, только когда картина видна целиком: настройка доступа в проекте одна, все ее шаблоны заданы строками,
 * а встроенных способов входа (форма, Basic, OAuth 2) в ней нет
 */
@Component
public class AnonymousEndpointBehindLoginRule extends AbstractContextRule {
    private static final Set<String> CONTROLLERS = Set.of("RestController", "Controller");
    // Вход делает сам Spring Security: логин с паролем либо токен читает его фильтр, а не обработчик
    private static final Set<String> BUILT_IN_LOGINS = Set.of(
            "httpBasic", "formLogin", "oauth2Login", "oauth2ResourceServer", "saml2Login", "x509", "jee",
            "openidLogin", "oneTimeTokenLogin", "webAuthn", "oauth2Client");
    // Цепочка относится только к части адресов: рядом может быть другая, со своими правилами
    private static final Set<String> SCOPING = Set.of(
            "securityMatcher", "securityMatchers", "requestMatcher", "antMatcher", "mvcMatcher", "regexMatcher");
    // Общая приставка к адресам обработчиков, которой в аннотациях нет
    private static final String ADD_PATH_PREFIX = "addPathPrefix";
    private static final Set<String> PATH_PREFIX_KEYS = Set.of("spring.mvc.servlet.path", "spring.webflux.base-path");
    private static final String ROOT = "/";
    private static final String DISABLE = "disable";
    // Свой фильтр, который сам принимает логин и пароль по адресу входа
    private static final Pattern LOGIN_FILTER =
            Pattern.compile(".*(AuthenticationProcessingFilter|UsernamePasswordAuthenticationFilter)$");

    private static final Set<String> OPEN_ACCESS = Set.of("permitAll", "anonymous");
    private static final Set<String> CLOSED_ACCESS = Set.of(
            "authenticated", "fullyAuthenticated", "hasRole", "hasAnyRole", "hasAuthority", "hasAnyAuthority",
            "denyAll");

    private static final String SET_AUTHENTICATION = "setAuthentication";
    private static final String AUTHENTICATE = "authenticate";
    private static final Set<String> AUTHENTICATION_MANAGERS = Set.of(
            "AuthenticationManager", "ReactiveAuthenticationManager", "AuthenticationProvider", "ProviderManager",
            "DaoAuthenticationProvider");
    private static final Pattern AUTHENTICATION_MANAGER_NAME =
            Pattern.compile("(?i).*auth(entication)?(Manager|Provider)$");
    // Имя метода или последняя часть адреса: /auth/login, signIn(...)
    private static final Pattern LOGIN_NAME = Pattern.compile(
            "(?i)(log[-_]?in|sign[-_]?in|sign[-_]?up|auth|authenticate|authentication|register|registration)");
    private static final Pattern WEBHOOK_NAME = Pattern.compile("(?i).*(web[-_]?hook|callback).*");
    private static final Set<String> REQUEST_VALUES = Set.of("PathVariable", "RequestHeader", "RequestParam");
    private static final Pattern SIGNATURE_NAME = Pattern.compile("(?i).*signature.*");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/}]*}");
    private static final Pattern SLASHES = Pattern.compile("/{2,}");
    private static final String SLASH = "/";
    // Обработчик и три вызова вглубь
    private static final int MAX_DEPTH = 4;

    private enum Access {
        OPEN, CLOSED, UNKNOWN
    }

    private record Decision(Access access, SecurityMatchers.Matcher matcher) {
    }

    @Override
    public String code() {
        return RuleCodes.ANONYMOUS_ENDPOINT_BEHIND_LOGIN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет обработчики с настройкой Spring Security и ищет вход и вебхуки, закрытые правилом"
                + " \"только для вошедших\"";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<SourceFile> sources = context.sources();
        Optional<MethodDeclaration> configuration = onlyConfiguration(sources);
        if (configuration.isEmpty() || hasOtherWayIn(sources) || hasPathPrefix(context)) {
            return List.of();
        }
        List<SecurityMatchers.Matcher> matchers = SecurityMatchers.of(configuration.get());
        List<String> ignored = new ArrayList<>();
        for (SourceFile source : sources) {
            Optional<List<String>> patterns = SecurityMatchers.ignored(source.unit());
            if (patterns.isEmpty()) {
                return List.of();
            }
            ignored.addAll(patterns.get());
        }
        boolean readable = matchers.stream().allMatch(matcher -> matcher.any() || matcher.patterns() != null);
        boolean closedByDefault = matchers.stream()
                .filter(SecurityMatchers.Matcher::any)
                .anyMatch(matcher -> accessOf(matcher) == Access.CLOSED);
        if (!readable || !closedByDefault) {
            return List.of();
        }

        ProjectClasses classes = ProjectClasses.of(sources);
        CallGraph graph = CallGraph.of(sources);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile source : sources) {
            for (ClassOrInterfaceDeclaration type : source.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!Annotations.hasAny(type, CONTROLLERS) || TestClasses.isInside(type)) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    Handlers.of(method, classes)
                            .flatMap(handler -> check(type, handler, matchers, ignored, graph))
                            .ifPresent(message -> violations.add(violation(source, method, message)));
                }
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
        return ErrorType.LOGICAL;
    }

    private Optional<String> check(ClassOrInterfaceDeclaration type, Handlers.Handler handler,
                                   List<SecurityMatchers.Matcher> matchers, List<String> ignored, CallGraph graph) {
        List<String> paths = pathsOf(type, handler);
        if (paths.isEmpty()) {
            return Optional.empty();
        }
        String httpMethod = Mappings.httpMethod(handler.mapping());
        // Закрыты должны быть все адреса обработчика: иначе один из них рабочий
        List<Decision> decisions = paths.stream().map(path -> decide(path, httpMethod, matchers, ignored)).toList();
        if (decisions.stream().anyMatch(decision -> decision.access() != Access.CLOSED)) {
            return Optional.empty();
        }
        String name = type.getNameAsString() + "." + handler.method().getNameAsString();
        String address = (httpMethod.isEmpty() ? "" : httpMethod + " ") + paths.get(0);
        String rule = describe(decisions.get(0).matcher());
        if (isLogin(handler, paths, graph)) {
            return Optional.of("Вход '" + name + "' (" + address + ") закрыт правилом " + rule + ": обработчик сам"
                    + " проводит вход пользователя, но вызвать его может только тот, кто уже вошел, -"
                    + " неавторизованный запрос получит 401 или 403 и до обработчика не дойдет; откройте адрес:"
                    + " requestMatchers(\"" + paths.get(0) + "\").permitAll() выше этого правила");
        }
        if (isWebhook(type, handler, paths)) {
            return Optional.of("Вебхук '" + name + "' (" + address + ") закрыт правилом " + rule + ": внешняя система"
                    + " приходит без сессии и предъявляет секрет, который обработчик проверяет сам, но Spring Security"
                    + " ответит ей 401 или 403 раньше, и вызов до обработчика не дойдет; откройте адрес:"
                    + " requestMatchers(\"" + PATH_VARIABLE.matcher(paths.get(0)).replaceAll("*")
                    + "\").permitAll() выше этого правила");
        }
        return Optional.empty();
    }

    // Настройка доступа в проекте одна: при нескольких цепочках запрос может обслуживать любая из них
    private Optional<MethodDeclaration> onlyConfiguration(List<SourceFile> sources) {
        List<MethodDeclaration> found = sources.stream()
                .flatMap(source -> source.findAll(MethodDeclaration.class).stream())
                .filter(method -> SecurityMatchers.configures(method) && !TestClasses.isInside(method))
                .toList();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * @return true, если войти можно и помимо своих обработчиков либо цепочка относится не ко всем адресам:
     * тогда закрытый обработчик входа может быть частью задуманной схемы (токен в обмен на Basic)
     */
    private boolean hasOtherWayIn(List<SourceFile> sources) {
        for (SourceFile source : sources) {
            boolean configured = source.findAll(MethodCallExpr.class).stream()
                    .filter(call -> !TestClasses.isInside(call))
                    .anyMatch(call -> SCOPING.contains(call.getNameAsString())
                            || ADD_PATH_PREFIX.equals(call.getNameAsString())
                            || BUILT_IN_LOGINS.contains(call.getNameAsString()) && !isDisabled(call));
            boolean loginFilter = source.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(type -> !TestClasses.isInside(type))
                    .flatMap(type -> type.getExtendedTypes().stream())
                    .anyMatch(parent -> LOGIN_FILTER.matcher(parent.getNameAsString()).matches());
            if (configured || loginFilter) {
                return true;
            }
        }
        return false;
    }

    // .httpBasic(AbstractHttpConfigurer::disable), .formLogin(form -> form.disable()), .httpBasic().disable()
    private boolean isDisabled(MethodCallExpr call) {
        boolean byArgument = call.getArguments().stream().anyMatch(argument -> argument.toString().contains(DISABLE));
        boolean byNextCall = call.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr next && DISABLE.equals(next.getNameAsString()))
                .isPresent();
        return byArgument || byNextCall;
    }

    private boolean hasPathPrefix(ScanContext context) {
        return context.configFiles().stream()
                .filter(configFile -> !configFile.isNonProduction())
                .anyMatch(configFile -> PATH_PREFIX_KEYS.stream()
                        .flatMap(key -> configFile.find(key).stream())
                        .anyMatch(property -> !ROOT.equals(property.value().trim()) && !property.value().isBlank()));
    }

    /**
     * @return адреса обработчика вместе с приставкой класса; пусто, если хоть один задан не строкой
     */
    private List<String> pathsOf(ClassOrInterfaceDeclaration type, Handlers.Handler handler) {
        // Приставку Spring берет с класса, а если на нем ее нет - с интерфейса, где объявлен обработчик
        Optional<AnnotationExpr> typeMapping = Mappings.find(type).or(() -> handler.declaration()
                .findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(Mappings::find));
        if (Mappings.hasUnknownPath(handler.mapping())
                || typeMapping.filter(Mappings::hasUnknownPath).isPresent()) {
            return List.of();
        }
        List<String> prefixes = typeMapping.map(Mappings::paths).orElse(List.of(""));
        List<String> paths = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String path : Mappings.paths(handler.mapping())) {
                String joined = SLASHES.matcher(SLASH + prefix + SLASH + path).replaceAll(SLASH);
                paths.add(joined.length() > 1 && joined.endsWith(SLASH)
                        ? joined.substring(0, joined.length() - 1)
                        : joined);
            }
        }
        // ${api.prefix}/login: приставка приходит из настроек
        return paths.stream().anyMatch(path -> path.contains("$")) ? List.of() : paths;
    }

    // Действует первое подходящее правило, как и в самом Spring Security
    private Decision decide(String path, String httpMethod, List<SecurityMatchers.Matcher> matchers,
                            List<String> ignored) {
        if (ignored.stream().anyMatch(pattern -> SecurityMatchers.matches(pattern, path))) {
            return new Decision(Access.OPEN, null);
        }
        for (SecurityMatchers.Matcher matcher : matchers) {
            if (!matcher.any()) {
                boolean samePath = matcher.patterns().isEmpty() || matcher.patterns().stream()
                        .anyMatch(pattern -> SecurityMatchers.matches(pattern, path));
                if (!samePath) {
                    continue;
                }
                // Правило только для одного HTTP-метода, а метод обработчика неизвестен
                if (matcher.method() != null && httpMethod.isEmpty()) {
                    return new Decision(Access.UNKNOWN, matcher);
                }
                if (matcher.method() != null && !matcher.method().equalsIgnoreCase(httpMethod)) {
                    continue;
                }
            }
            return new Decision(accessOf(matcher), matcher);
        }
        return new Decision(Access.UNKNOWN, null);
    }

    private Access accessOf(SecurityMatchers.Matcher matcher) {
        String name = accessName(matcher);
        if (OPEN_ACCESS.contains(name)) {
            return Access.OPEN;
        }
        // access(...) и свои условия: кого они пускают, по коду не узнать
        return CLOSED_ACCESS.contains(name) ? Access.CLOSED : Access.UNKNOWN;
    }

    private String accessName(SecurityMatchers.Matcher matcher) {
        int arguments = matcher.access().indexOf('[');
        return arguments < 0 ? matcher.access() : matcher.access().substring(0, arguments);
    }

    private String describe(SecurityMatchers.Matcher matcher) {
        String target = matcher.any()
                ? matcher.call().getNameAsString() + "()"
                : matcher.call().getNameAsString() + "(\"" + String.join("\", \"", matcher.patterns()) + "\")";
        int arguments = matcher.access().indexOf('[');
        String values = arguments < 0 ? "" : matcher.access().substring(arguments + 1, matcher.access().length() - 1);
        return "'" + target + "." + accessName(matcher) + "(" + values + ")'";
    }

    /**
     * Вход: так назван сам обработчик или его адрес, и по ходу выполнения он кладет пользователя в контекст
     * безопасности либо проверяет логин и пароль менеджером аутентификации. Смена пользователя администратором
     * и повторное подтверждение пароля так не называются
     */
    private boolean isLogin(Handlers.Handler handler, List<String> paths, CallGraph graph) {
        boolean named = LOGIN_NAME.matcher(handler.method().getNameAsString()).matches()
                || paths.stream().map(this::lastSegment).anyMatch(segment -> LOGIN_NAME.matcher(segment).matches());
        return named && CallChains.find(graph, handler.method(), MAX_DEPTH, method -> false,
                node -> node instanceof MethodCallExpr call && establishesAuthentication(call)
                        ? Optional.of(call.getNameAsString())
                        : Optional.<String>empty()).isPresent();
    }

    private String lastSegment(String path) {
        String fixed = PATH_VARIABLE.matcher(path).replaceAll("");
        String trimmed = fixed.endsWith(SLASH) ? fixed.substring(0, fixed.length() - 1) : fixed;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private boolean establishesAuthentication(MethodCallExpr call) {
        if (SET_AUTHENTICATION.equals(call.getNameAsString())) {
            return call.getArguments().size() == 1 && !call.getArgument(0).isNullLiteralExpr();
        }
        return AUTHENTICATE.equals(call.getNameAsString()) && call.getScope()
                .filter(scope -> LocalTypes.isAnyOf(scope, AUTHENTICATION_MANAGERS,
                        () -> AUTHENTICATION_MANAGER_NAME.matcher(MethodCalls.receiverName(scope)).matches()))
                .isPresent();
    }

    /**
     * Вебхук: так назван класс, метод или адрес, и вызывающий предъявляет секрет либо подпись - частью адреса,
     * параметром или заголовком. Такой обработчик проверяет вызывающего сам, сессия ему не нужна
     */
    private boolean isWebhook(ClassOrInterfaceDeclaration type, Handlers.Handler handler, List<String> paths) {
        boolean named = WEBHOOK_NAME.matcher(type.getNameAsString()).matches()
                || WEBHOOK_NAME.matcher(handler.method().getNameAsString()).matches()
                || paths.stream().anyMatch(path -> WEBHOOK_NAME.matcher(path).matches());
        return named && handler.method().getParameters().stream().anyMatch(parameter -> isSecret(handler, parameter));
    }

    private boolean isSecret(Handlers.Handler handler, Parameter parameter) {
        Parameter declared = handler.declared(parameter);
        Optional<AnnotationExpr> source = Annotations.find(parameter, REQUEST_VALUES)
                .or(() -> Annotations.find(declared, REQUEST_VALUES));
        if (source.isEmpty()) {
            return false;
        }
        // @RequestHeader("X-Hub-Signature") String header: имя задано в аннотации
        String name = source.get().findFirst(StringLiteralExpr.class)
                .map(StringLiteralExpr::getValue)
                .filter(value -> !value.isBlank())
                .orElseGet(parameter::getNameAsString);
        return Secrets.isSecretName(name) || Secrets.isSecretName(parameter.getNameAsString())
                || SIGNATURE_NAME.matcher(name).matches();
    }
}
