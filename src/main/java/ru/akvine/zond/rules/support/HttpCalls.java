package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Синхронные HTTP-вызовы: RestTemplate, WebClient, HTTP-клиенты JDK и Apache, Feign и Spring HTTP Interface
 */
@UtilityClass
public class HttpCalls {
    // HTTP-клиент узнаем по типу, а если тип определить не удалось - по имени объекта либо по характерному методу
    private static final Set<String> HTTP_CLIENT_TYPES = Set.of(
            "RestTemplate", "RestOperations", "WebClient", "RestClient", "HttpClient", "CloseableHttpClient",
            "OkHttpClient");

    // У URL сетевые только openStream() и openConnection(), а не getHost() или getPath()
    private static final Set<String> URL_TYPES = Set.of("URL", "URLConnection");

    // Имя, которое говорит об HTTP само
    // Имя кончается на клиента: httpClient, paymentRestTemplate. httpClientDefinition, httpClientConfig - его
    // описание или настройки, а не сам клиент
    private static final Pattern HTTP_CLIENT = Pattern.compile(
            ".*(resttemplate|webclient|restclient|httpclient|feign|feignclient)$", Pattern.CASE_INSENSITIVE);

    // Имя, которое может означать и HTTP-клиент (paymentClient), и клиента-покупателя (client, ownerClient):
    // одного его мало, смотрим еще на то, что с объектом делают
    private static final Pattern MAYBE_CLIENT = Pattern.compile(".*client$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GETTER = Pattern.compile("^(get|is)[A-Z].*");
    private static final Pattern SETTER = Pattern.compile("^set[A-Z].*");
    // client.isChildOfGroup(groupId), client.hasRole(role): проверка свойства клиента-покупателя, а не запрос
    private static final Pattern PREDICATE = Pattern.compile("^(is|has|can)[A-Z].*");

    // Интерфейс, по которому HTTP-клиента создает библиотека: Spring HTTP Interface, Feign, Retrofit
    private static final Set<String> DECLARATIVE_CLIENT_ANNOTATIONS = Set.of(
            "FeignClient", "HttpExchange", "GetExchange", "PostExchange", "PutExchange", "DeleteExchange",
            "PatchExchange", "RequestLine", "GET", "POST", "PUT", "DELETE", "PATCH");

    // Методы Optional, стрима и любого объекта: по ним видно, что получатель - не HTTP-клиент
    private static final Set<String> CONTAINER_METHODS = Set.of(
            "map", "flatMap", "filter", "orElse", "orElseGet", "orElseThrow", "isPresent", "isEmpty", "ifPresent",
            "ifPresentOrElse", "stream", "equals", "hashCode", "toString");

    private static final Set<String> HTTP_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation", "patchForObject",
            "exchange", "retrieve", "openConnection", "openStream");

    /**
     * @return описание вызова (restTemplate.postForObject), если узел - HTTP-вызов
     */
    public Optional<String> describe(Node node) {
        return describe(node, true);
    }

    /**
     * То же, но без догадки по имени вида paymentClient: только известные клиенты (по типу, по имени restTemplate,
     * webClient, httpClient, feign) и HTTP-методы. ChatClient или Kafka-клиент по одному имени сюда не попадут
     */
    public Optional<String> describeKnownClient(Node node) {
        return describe(node, false);
    }

    /**
     * @return описание ссылки (restTemplate::getForObject, priceClient::fetch), если она указывает на метод
     * HTTP-клиента: известного по типу либо описанного интерфейсом Feign / Spring HTTP Interface
     */
    public Optional<String> describeReference(MethodReferenceExpr reference, ProjectClasses classes) {
        Optional<String> type = MethodReferences.scopeType(reference);
        boolean known = type.filter(HTTP_CLIENT_TYPES::contains).isPresent()
                && HTTP_METHODS.contains(reference.getIdentifier());
        boolean declarative = type.flatMap(classes::find).filter(HttpCalls::isDeclarativeClient).isPresent();
        return known || declarative ? Optional.of(Nodes.text(reference)) : Optional.empty();
    }

    private Optional<String> describe(Node node, boolean guessByClientName) {
        if (!(node instanceof MethodCallExpr call) || call.getScope().isEmpty()) {
            return Optional.empty();
        }

        Expression scope = call.getScope().get();
        String receiver = MethodCalls.receiverName(scope);
        return isHttpClient(call, scope, receiver, guessByClientName)
                ? Optional.of(receiver + "." + call.getNameAsString())
                : Optional.empty();
    }

    private boolean isHttpClient(MethodCallExpr call, Expression scope, String receiver, boolean guessByClientName) {
        String method = call.getNameAsString();
        if (Types.isKindOf(scope, URL_TYPES).orElse(false)) {
            return HTTP_METHODS.contains(method);
        }
        // Тип из JDK, не связанный с сетью (Exchanger.exchange(), Map client), под имена подходит случайно
        // Если решатель тип не нашел, а объявлено поле RestTemplate restTemplate - судим по объявлению
        Optional<Boolean> byType = Types.matches(scope, HTTP_CLIENT_TYPES::contains)
                .or(() -> LocalTypes.findDeclaration(scope)
                        .flatMap(LocalTypes::declaredType)
                        .filter(HTTP_CLIENT_TYPES::contains)
                        .map(type -> true));
        // restTemplate.getMessageConverters(), restTemplate.setErrorHandler(...) - настройка клиента, а не запрос
        if (byType.isPresent()) {
            return byType.get() && !isAccessor(call);
        }

        // Свой класс проекта: сущность Client, модель ClientModel, обертка над RestTemplate. По имени о нем
        // не судим: настоящий HTTP-вызов внутри его методов найдется при обходе цепочки вызовов
        Optional<TypeDeclaration<?>> projectType = Types.projectType(scope);
        if (projectType.isPresent()) {
            return isDeclarativeClient(projectType.get());
        }

        return HTTP_METHODS.contains(method) || isClientByName(call, scope, receiver, guessByClientName);
    }

    // Тип неизвестен: судим по имени получателя - apiClient, paymentGateway
    private boolean isClientByName(MethodCallExpr call, Expression scope, String receiver, boolean guessByClientName) {
        Expression value = Nodes.unwrap(scope);
        // HttpClientDefinition.lookup(...): статический метод класса, а не запрос через объект-клиент
        boolean onType = value.isNameExpr() && Character.isUpperCase(receiver.charAt(0));
        if (onType || isContainerCall(call, value)) {
            return false;
        }
        // restTemplate.getMessageConverters().remove(converter): работа с настройками клиента, а не запрос
        boolean onAccessorResult = value.isMethodCallExpr() && isAccessor(value.asMethodCallExpr());
        return HTTP_CLIENT.matcher(receiver).matches() && !onAccessorResult
                || guessByClientName && MAYBE_CLIENT.matcher(receiver).matches() && !isDataAccess(call, scope);
    }

    // describeClient(expression).map(...): получатель - результат вызова с аргументами, то есть то, что метод
    // вернул, а не объект-клиент; map, filter, orElse - методы Optional и стрима, у клиента их нет
    private boolean isContainerCall(MethodCallExpr call, Expression receiver) {
        return receiver.isMethodCallExpr() && !receiver.asMethodCallExpr().getArguments().isEmpty()
                || CONTAINER_METHODS.contains(call.getNameAsString());
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
        if (isAccessor(call) || PREDICATE.matcher(call.getNameAsString()).matches()) {
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
}
