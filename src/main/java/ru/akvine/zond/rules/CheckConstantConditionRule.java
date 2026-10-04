package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Set;

@Component
public class CheckConstantConditionRule extends AbstractFlowRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_CONSTANT_CONDITION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Прослеживает значения по ходу метода и ищет условия, которые всегда истинны либо всегда ложны";
    }

    @Override
    Set<FlowAnalysis.Kind> kinds() {
        return Set.of(FlowAnalysis.Kind.CONSTANT_CONDITION);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
