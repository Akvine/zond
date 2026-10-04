package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractFlowRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.flow.FlowAnalysis;

import java.util.Set;

@Component
public class CheckAlwaysNullDereferenceRule extends AbstractFlowRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_ALWAYS_NULL_DEREFERENCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Прослеживает значения по ходу метода и ищет обращение к переменной, которая в этой точке всегда равна null";
    }

    @Override
    protected Set<FlowAnalysis.Kind> kinds() {
        return Set.of(FlowAnalysis.Kind.NULL_DEREFERENCE);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
