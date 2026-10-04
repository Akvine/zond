package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractEntityMethodWithRelationsRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.Set;

@Component
public class CheckEntityEqualsWithRelationsRule extends AbstractEntityMethodWithRelationsRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_ENTITY_EQUALS_WITH_RELATIONS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет equals и hashCode сущностей, которые используют поля-связи";
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
        return Set.of("equals", "hashCode");
    }

    @Override
    protected String lombokAnnotation() {
        return "EqualsAndHashCode";
    }

    @Override
    protected String message(String entity, String source, String relations) {
        return source + " сущности '" + entity + "' использует связи (" + relations + "): сравнение загрузит"
                + " связанные сущности из БД, а на двусторонней связи уйдет в бесконечную рекурсию;"
                + " сравнивайте сущности по идентификатору";
    }
}
