package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractFlowRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.flow.FlowAnalysis;

import java.util.Set;

@Component
public class EmptyOptionalAccessRule extends AbstractFlowRule {
    @Override
    public String code() {
        return RuleCodes.EMPTY_OPTIONAL_ACCESS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Прослеживает значения по ходу метода и ищет get() и orElseThrow() у Optional, который в этой точке всегда пуст";
    }

    @Override
    protected Set<FlowAnalysis.Kind> kinds() {
        return Set.of(FlowAnalysis.Kind.EMPTY_OPTIONAL);
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
