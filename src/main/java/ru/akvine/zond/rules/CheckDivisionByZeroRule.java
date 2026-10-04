package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Set;

@Component
public class CheckDivisionByZeroRule extends AbstractFlowRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_DIVISION_BY_ZERO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Прослеживает значения по ходу метода и ищет деление на переменную, которая в этой точке всегда равна нулю";
    }

    @Override
    Set<FlowAnalysis.Kind> kinds() {
        return Set.of(FlowAnalysis.Kind.DIVISION_BY_ZERO);
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
