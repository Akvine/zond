package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class HttpClientWithoutTimeoutRule extends AbstractRule {
    private static final Set<String> CLIENT_TYPES = Set.of("RestTemplate", "OkHttpClient");
    private static final String HTTP_CLIENT = "HttpClient";
    private static final String NEW_HTTP_CLIENT = "newHttpClient";

    // Apache HttpClient 4 и 5: HttpClients.createDefault(), HttpClients.custom()...build(), HttpClientBuilder.create()
    private static final Set<String> APACHE_FACTORIES = Set.of("HttpClients", "HttpAsyncClients");
    private static final Set<String> APACHE_FACTORY_METHODS =
            Set.of("createDefault", "createSystem", "createMinimal", "custom");
    private static final Set<String> APACHE_BUILDERS = Set.of("HttpClientBuilder", "HttpAsyncClientBuilder");
    private static final String CREATE = "create";
    // Части клиента, которые в цепочку сборки передают готовыми: сроки могли задать там, где их собирали
    private static final Set<String> APACHE_PARTS = Set.of(
            "setConnectionManager", "setDefaultRequestConfig", "setDefaultConnectionConfig", "setDefaultSocketConfig");

    private static final String OPEN_CONNECTION = "openConnection";
    private static final Set<String> HTTP_CONNECTIONS = Set.of("HttpURLConnection", "HttpsURLConnection");

    // Вызов, который задает срок клиенту: setConnectTimeout, readTimeout, callTimeout, responseTimeout,
    // timeout(Duration). timeoutDuration у ограничителя времени Resilience4j и getPollingTimeout сюда не относятся
    private static final Pattern TIMEOUT_SETTER = Pattern.compile(
            "(?i)(set|with)?(default)?(connect(ion)?(request)?|read|write|socket|so|response|call|request|pool|idle"
                    + "|handshake|acquire|keepalive)?timeout(millis|ms|seconds|inmillis)?");
    // Типы, которыми сроки задают: RequestConfig у Apache HttpClient, Timeout у пятой версии, построитель Spring
    private static final Set<String> TIMEOUT_TYPES = Set.of(
            "RequestConfig", "ConnectionConfig", "SocketConfig", "Timeout", "RestTemplateBuilder",
            "ClientHttpRequestFactorySettings", "HttpClientSettings");

    @Override
    public String code() {
        return RuleCodes.HTTP_CLIENT_WITHOUT_TIMEOUT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет HTTP-клиенты и соединения, созданные без таймаутов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Сроки где-то в этом файле задают: к какому клиенту они относятся, по тексту не понять
        if (setsTimeouts(sourceFile)) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (Expression expression : sourceFile.findAll(Expression.class)) {
            describeClient(expression).ifPresent(client -> violations.add(violation(sourceFile, expression,
                    "'" + client + "' без таймаутов: по умолчанию клиент ждет ответа бесконечно,"
                            + " зависший внешний сервис займет все потоки приложения; задайте"
                            + " таймауты соединения и чтения")));
            if (expression.isMethodCallExpr() && isHttpConnection(expression.asMethodCallExpr())) {
                violations.add(violation(sourceFile, expression,
                        "'" + expression + "' без таймаутов: у HttpURLConnection сроки соединения и чтения по"
                                + " умолчанию не ограничены, зависший сервер займет поток навсегда; вызовите"
                                + " setConnectTimeout и setReadTimeout"));
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    // new RestTemplate(), new OkHttpClient(), HttpClient.newHttpClient(), HttpClients.createDefault()
    private Optional<String> describeClient(Expression expression) {
        if (expression.isObjectCreationExpr()) {
            String type = expression.asObjectCreationExpr().getType().getNameAsString();
            boolean bare = CLIENT_TYPES.contains(type) && expression.asObjectCreationExpr().getArguments().isEmpty();
            return bare ? Optional.of("new " + type + "()") : Optional.empty();
        }
        if (!expression.isMethodCallExpr()) {
            return Optional.empty();
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        if (MethodCalls.isCallOn(call, HTTP_CLIENT, NEW_HTTP_CLIENT)) {
            return Optional.of("HttpClient.newHttpClient()");
        }
        return isApacheClient(call) && !takesReadyParts(call)
                ? Optional.of(call.getScope().orElseThrow() + "." + call.getNameAsString() + "()")
                : Optional.empty();
    }

    private boolean isApacheClient(MethodCallExpr call) {
        if (!call.getArguments().isEmpty() || call.getScope().isEmpty() || !call.getScope().get().isNameExpr()) {
            return false;
        }
        String owner = call.getScope().get().asNameExpr().getNameAsString();
        String method = call.getNameAsString();
        return APACHE_FACTORIES.contains(owner) && APACHE_FACTORY_METHODS.contains(method)
                || APACHE_BUILDERS.contains(owner) && CREATE.equals(method);
    }

    /**
     * {@code HttpClients.custom().setConnectionManager(connectionManager).build()}, где connectionManager - поле
     * или параметр: менеджер соединений собран в другом месте, и сроки могли задать там
     */
    private boolean takesReadyParts(MethodCallExpr creation) {
        Optional<MethodCallExpr> next = chained(creation);
        while (next.isPresent()) {
            MethodCallExpr step = next.get();
            if (APACHE_PARTS.contains(step.getNameAsString())
                    && step.getArguments().stream().map(Nodes::unwrap).anyMatch(this::isMadeElsewhere)) {
                return true;
            }
            next = chained(step);
        }
        return false;
    }

    // Следующий вызов той же цепочки: custom() -> setConnectionManager(...) -> build()
    private Optional<MethodCallExpr> chained(MethodCallExpr call) {
        return call.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr)
                .map(parent -> (MethodCallExpr) parent)
                .filter(parent -> parent.getScope().filter(scope -> scope == call).isPresent());
    }

    // Локальная переменная и свой метод собраны в этом же файле, и сроков в нем нет; остальное пришло со стороны
    private boolean isMadeElsewhere(Expression part) {
        if (part.isObjectCreationExpr()) {
            return false;
        }
        if (part.isMethodCallExpr()) {
            return part.asMethodCallExpr().getScope().isPresent();
        }
        if (!part.isNameExpr()) {
            return true;
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(part);
        return declaration.isEmpty() || declaration.get() instanceof Parameter
                || declaration.get().getParentNode().filter(parent -> parent instanceof FieldDeclaration).isPresent();
    }

    // (HttpURLConnection) url.openConnection(), HttpURLConnection connection = ...openConnection()
    private boolean isHttpConnection(MethodCallExpr call) {
        if (!OPEN_CONNECTION.equals(call.getNameAsString()) || call.getScope().isEmpty()) {
            return false;
        }
        Node parent = call.getParentNode().orElse(null);
        while (parent instanceof Expression wrapper && wrapper.isEnclosedExpr()) {
            parent = wrapper.getParentNode().orElse(null);
        }
        if (parent instanceof CastExpr cast) {
            return HTTP_CONNECTIONS.contains(LocalTypes.typeName(cast.getType()));
        }
        return parent instanceof VariableDeclarator variable
                && HTTP_CONNECTIONS.contains(LocalTypes.typeName(variable.getType()));
    }

    private boolean setsTimeouts(SourceFile sourceFile) {
        boolean byCall = sourceFile.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .anyMatch(this::isTimeoutSetter);
        boolean byReference = sourceFile.findAll(MethodReferenceExpr.class).stream()
                .map(MethodReferenceExpr::getIdentifier)
                .anyMatch(this::isTimeoutSetter);
        boolean byType = sourceFile.findAll(ClassOrInterfaceType.class).stream()
                .anyMatch(type -> TIMEOUT_TYPES.contains(type.getNameAsString()))
                || sourceFile.findAll(NameExpr.class).stream()
                .anyMatch(name -> TIMEOUT_TYPES.contains(name.getNameAsString()));
        return byCall || byReference || byType;
    }

    private boolean isTimeoutSetter(String method) {
        return TIMEOUT_SETTER.matcher(method).matches();
    }
}
