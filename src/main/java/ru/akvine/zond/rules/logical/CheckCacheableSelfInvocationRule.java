package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractSelfInvocationRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.Set;

@Component
public class CheckCacheableSelfInvocationRule extends AbstractSelfInvocationRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_CACHEABLE_SELF_INVOCATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы @Cacheable / @CachePut / @CacheEvict-методов из того же класса";
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
        return Set.of("Cacheable", "CachePut", "CacheEvict");
    }

    @Override
    protected String consequence(String annotation) {
        return "кэш не будет ни прочитан, ни обновлен, метод выполнится как обычный";
    }
}
