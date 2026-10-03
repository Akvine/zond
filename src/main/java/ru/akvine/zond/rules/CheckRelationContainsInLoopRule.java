package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

@Component
public class CheckRelationContainsInLoopRule extends AbstractRelationAccessInLoopRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_RELATION_CONTAINS_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет getRelation().contains() для элемента цикла";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    @Override
    protected String collectionMethod() {
        return "contains";
    }

    @Override
    protected int argumentsCount() {
        return 1;
    }

    @Override
    protected String message(String access) {
        return "'" + access + "(...)' для каждого элемента цикла: если это LAZY-связь, ради одной проверки на"
                + " каждой итерации загружается вся коллекция отдельным запросом (N+1); проверьте"
                + " принадлежность запросом с EXISTS либо загрузите связи заранее через JOIN FETCH";
    }
}
