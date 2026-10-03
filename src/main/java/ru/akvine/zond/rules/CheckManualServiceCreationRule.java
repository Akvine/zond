package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckManualServiceCreationRule extends AbstractManualBeanCreationRule {
    // OrderService, OrderServiceImpl
    private static final Pattern SERVICE = Pattern.compile(".*Service(Impl)?$");

    @Override
    public String code() {
        return RuleCodes.CHECK_MANUAL_SERVICE_CREATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет создание сервисов через new в обход Spring";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    @Override
    protected Pattern beanTypeName() {
        return SERVICE;
    }

    @Override
    protected Set<String> notBeanTypes() {
        // Классы JDK, а не бины приложения
        return Set.of("ExecutorCompletionService");
    }

    @Override
    protected String beanKind() {
        return "сервис";
    }
}
