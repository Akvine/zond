package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Set;

@Component
public class CheckEntityToStringWithRelationsRule extends AbstractEntityMethodWithRelationsRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_ENTITY_TO_STRING_WITH_RELATIONS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет toString сущностей, который выводит поля-связи";
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
    protected Set<String> methodNames() {
        return Set.of("toString");
    }

    @Override
    protected String lombokAnnotation() {
        return "ToString";
    }

    @Override
    protected String message(String entity, String source, String relations) {
        return source + " сущности '" + entity + "' выводит связи (" + relations + "): обычная запись в лог"
                + " выполнит SQL-запрос за LAZY-связью либо упадет с LazyInitializationException, а на"
                + " двусторонней связи уйдет в рекурсию; исключите связи из toString";
    }
}
