package ru.akvine.zond.rules.codesmell;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractFlowRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.flow.FlowAnalysis;

import java.util.Set;

@Component
public class CheckUnreachableCodeRule extends AbstractFlowRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_UNREACHABLE_CODE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Прослеживает ход выполнения метода и ищет код, до которого выполнение никогда не доходит";
    }

    @Override
    protected Set<FlowAnalysis.Kind> kinds() {
        return Set.of(FlowAnalysis.Kind.UNREACHABLE_CODE);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }
}
