package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractSelfInvocationRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.Set;

@Component
public class RetryableSelfInvocationRule extends AbstractSelfInvocationRule {

    @Override
    public String code() {
        return RuleCodes.RETRYABLE_SELF_INVOCATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы @Retryable-методов из того же класса";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    @Override
    protected Set<String> annotations() {
        return Set.of("Retryable");
    }

    @Override
    protected String consequence(String annotation) {
        return "повторных попыток не будет, первая же ошибка уйдет вызывающему коду";
    }
}
