package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractFlowRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.flow.FlowAnalysis;

import java.util.Set;

@Component
public class CheckIndexOutOfBoundsRule extends AbstractFlowRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_INDEX_OUT_OF_BOUNDS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Прослеживает значения по ходу метода и ищет индекс за границами массива и результат indexOf, использованный без проверки на -1";
    }

    @Override
    protected Set<FlowAnalysis.Kind> kinds() {
        return Set.of(FlowAnalysis.Kind.INDEX_OUT_OF_BOUNDS);
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
