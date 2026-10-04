package ru.akvine.zond.rules.concurrency;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractSelfInvocationRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.Set;

@Component
public class CheckAsyncSelfInvocationRule extends AbstractSelfInvocationRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_ASYNC_SELF_INVOCATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы @Async-методов из того же класса";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    @Override
    protected Set<String> annotations() {
        return Set.of("Async");
    }

    @Override
    protected String consequence(String annotation) {
        return "метод выполнится синхронно, в потоке вызывающего кода";
    }
}
