package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckTransactionalHttpCallRule extends AbstractTransactionalBlockingCallRule {
    // Типы не разрешаем, поэтому HTTP-клиент узнаем по имени объекта либо по характерному методу
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

        String receiver = MethodCalls.receiverName(call.getScope().get());
        boolean isHttp = HTTP_METHODS.contains(call.getNameAsString()) || HTTP_CLIENT.matcher(receiver).matches();
        return isHttp ? Optional.of(receiver + "." + call.getNameAsString()) : Optional.empty();
    }

    @Override
    protected String message(String method, String call) {
        return "HTTP-вызов '" + call + "' внутри @Transactional-метода '" + method + "': транзакция и соединение"
                + " с БД удерживаются все время сетевого вызова, при медленном ответе пул соединений исчерпается;"
                + " вынесите вызов за пределы транзакции";
    }
}
