package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckTransactionalHttpCallRule extends AbstractTransactionalBlockingCallRule {
    // HTTP-клиент узнаем по типу, а если тип определить не удалось - по имени объекта либо по характерному методу
    private static final Set<String> HTTP_CLIENT_TYPES = Set.of(
            "RestTemplate", "RestOperations", "WebClient", "RestClient", "HttpClient", "CloseableHttpClient",
            "OkHttpClient");

    // У URL сетевые только openStream() и openConnection(), а не getHost() или getPath()
    private static final Set<String> URL_TYPES = Set.of("URL", "URLConnection");

    // Имя, которое говорит об HTTP само
    private static final Pattern HTTP_CLIENT = Pattern.compile(
            ".*(resttemplate|webclient|restclient|httpclient|feign).*", Pattern.CASE_INSENSITIVE);

    // Имя, которое может означать и HTTP-клиент (paymentClient), и клиента-покупателя (client, ownerClient):
    // одного его мало, смотрим еще на то, что с объектом делают
    private static final Pattern MAYBE_CLIENT = Pattern.compile(".*client$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GETTER = Pattern.compile("^(get|is)[A-Z].*");
    private static final Pattern SETTER = Pattern.compile("^set[A-Z].*");

    // Интерфейс, по которому HTTP-клиента создает библиотека: Spring HTTP Interface, Feign, Retrofit
    private static final Set<String> DECLARATIVE_CLIENT_ANNOTATIONS = Set.of(
            "FeignClient", "HttpExchange", "GetExchange", "PostExchange", "PutExchange", "DeleteExchange",
            "PatchExchange", "RequestLine", "GET", "POST", "PUT", "DELETE", "PATCH");

    private static final Set<String> HTTP_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation", "patchForObject",
            "exchange", "retrieve", "openConnection", "openStream");

    @Override
    public String code() {
        return RuleCodes.CHECK_TRANSACTIONAL_HTTP_CALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет HTTP-вызовы внутри @Transactional-методов";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    @Override
    protected Optional<String> describeBlockingCall(Node node) {
        if (!(node instanceof MethodCallExpr call) || call.getScope().isEmpty()) {
            return Optional.empty();
        }

        Expression scope = call.getScope().get();
        String receiver = MethodCalls.receiverName(scope);
        return isHttpClient(call, scope, receiver)
                ? Optional.of(receiver + "." + call.getNameAsString())
                : Optional.empty();
    }

    private boolean isHttpClient(MethodCallExpr call, Expression scope, String receiver) {
        String method = call.getNameAsString();
        if (Types.isKindOf(scope, URL_TYPES).orElse(false)) {
            return HTTP_METHODS.contains(method);
        }
        // Тип из JDK, не связанный с сетью (Exchanger.exchange(), Map client), под имена подходит случайно
        Optional<Boolean> byType = Types.matches(scope, HTTP_CLIENT_TYPES::contains);
        if (byType.isPresent()) {
            return byType.get();
        }

        // Свой класс проекта: сущность Client, модель ClientModel, обертка над RestTemplate. По имени о нем
        // не судим: настоящий HTTP-вызов внутри его методов найдется при обходе цепочки вызовов
        Optional<TypeDeclaration<?>> projectType = Types.projectType(scope);
        if (projectType.isPresent()) {
            return isDeclarativeClient(projectType.get());
        }

        return HTTP_METHODS.contains(method)
                || HTTP_CLIENT.matcher(receiver).matches()
                || MAYBE_CLIENT.matcher(receiver).matches() && !isDataAccess(call, scope);
    }

    // Интерфейс без реализации в коде: аннотации на нем или на его методах описывают HTTP-запросы
    private boolean isDeclarativeClient(TypeDeclaration<?> type) {
        boolean isInterface = type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface();
        return isInterface && (Annotations.hasAny(type, DECLARATIVE_CLIENT_ANNOTATIONS)
                || type.getMethods().stream()
                .anyMatch(method -> Annotations.hasAny(method, DECLARATIVE_CLIENT_ANNOTATIONS)));
    }

    // client.getChatId(), order.getClient().getId(), entity.setClient(client).setActive(true):
    // чтение и запись свойств объекта с данными, а не обращение по сети
    private boolean isDataAccess(MethodCallExpr call, Expression scope) {
        if (isAccessor(call)) {
            return true;
        }
        Expression value = Nodes.unwrap(scope);
        return value.isMethodCallExpr() && isAccessor(value.asMethodCallExpr());
    }

    private boolean isAccessor(MethodCallExpr call) {
        String name = call.getNameAsString();
        return GETTER.matcher(name).matches() && call.getArguments().isEmpty()
                || SETTER.matcher(name).matches() && call.getArguments().size() == 1;
    }

    @Override
    protected String message(String method, String call) {
        return "HTTP-вызов '" + call + "' внутри @Transactional-метода '" + method + "': транзакция и соединение"
                + " с БД удерживаются все время сетевого вызова, при медленном ответе пул соединений исчерпается;"
                + " вынесите вызов за пределы транзакции";
    }
}
