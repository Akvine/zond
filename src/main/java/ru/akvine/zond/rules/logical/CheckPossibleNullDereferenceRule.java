package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractFlowRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.flow.FlowAnalysis;

import java.util.Set;

@Component
public class CheckPossibleNullDereferenceRule extends AbstractFlowRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_POSSIBLE_NULL_DEREFERENCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Прослеживает значения по ходу метода и через вызовы и ищет обращение без проверки к значению, которое на одном из путей равно null";
    }

    @Override
    protected Set<FlowAnalysis.Kind> kinds() {
        return Set.of(FlowAnalysis.Kind.POSSIBLE_NULL_DEREFERENCE);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
