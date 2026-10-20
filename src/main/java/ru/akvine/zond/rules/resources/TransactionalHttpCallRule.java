package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractTransactionalBlockingCallRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.HttpCalls;

import java.util.Optional;

@Component
public class TransactionalHttpCallRule extends AbstractTransactionalBlockingCallRule {

    @Override
    public String code() {
        return RuleCodes.TRANSACTIONAL_HTTP_CALL_RULE_CODE;
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
        return HttpCalls.describe(node);
    }

    @Override
    protected String message(String method, String call) {
        return "HTTP-вызов '" + call + "' внутри @Transactional-метода '" + method + "': транзакция и соединение"
                + " с БД удерживаются все время сетевого вызова, при медленном ответе пул соединений исчерпается;"
                + " вынесите вызов за пределы транзакции";
    }
}
