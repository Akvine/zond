package ru.akvine.zond.rules.performance;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractRelationAccessInLoopRule;
import ru.akvine.zond.rules.RuleCodes;

@Component
public class RelationSizeInLoopRule extends AbstractRelationAccessInLoopRule {

    @Override
    public String code() {
        return RuleCodes.RELATION_SIZE_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет getRelation().size() для элемента цикла";
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
        return "size";
    }

    @Override
    protected int argumentsCount() {
        return 0;
    }

    @Override
    protected String message(String access) {
        return "'" + access + "()' для каждого элемента цикла: если это LAZY-связь, ради одного числа на каждой"
                + " итерации загружается вся коллекция отдельным запросом (N+1); посчитайте количество одним"
                + " запросом с COUNT и GROUP BY";
    }
}
