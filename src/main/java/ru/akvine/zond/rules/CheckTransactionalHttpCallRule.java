package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
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
    private static final String FEIGN_CLIENT = "FeignClient";

    private static final Pattern HTTP_CLIENT = Pattern.compile(
            ".*(resttemplate|webclient|restclient|httpclient|feign).*|.*client$", Pattern.CASE_INSENSITIVE);

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
        return isHttpClient(scope, call.getNameAsString(), receiver)
                ? Optional.of(receiver + "." + call.getNameAsString())
                : Optional.empty();
    }

    private boolean isHttpClient(Expression scope, String method, String receiver) {
        // Интерфейс с @FeignClient - свой класс проекта, по имени типа его не узнать
        if (Types.annotations(scope).contains(FEIGN_CLIENT)) {
            return true;
        }
        if (Types.isKindOf(scope, URL_TYPES).orElse(false)) {
            return HTTP_METHODS.contains(method);
        }
        // Тип из JDK, не связанный с сетью (Exchanger.exchange(), Map client), под имена подходит случайно
        return Types.matches(scope, HTTP_CLIENT_TYPES::contains)
                .orElseGet(() -> HTTP_METHODS.contains(method) || HTTP_CLIENT.matcher(receiver).matches());
    }

    @Override
    protected String message(String method, String call) {
        return "HTTP-вызов '" + call + "' внутри @Transactional-метода '" + method + "': транзакция и соединение"
                + " с БД удерживаются все время сетевого вызова, при медленном ответе пул соединений исчерпается;"
                + " вынесите вызов за пределы транзакции";
    }
}
