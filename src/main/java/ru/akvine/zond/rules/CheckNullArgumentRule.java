package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Set;

@Component
public class CheckNullArgumentRule extends AbstractFlowRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_NULL_ARGUMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Прослеживает значения через вызовы и ищет передачу null в метод, который обращается к параметру без проверки";
    }

    @Override
    Set<FlowAnalysis.Kind> kinds() {
        return Set.of(FlowAnalysis.Kind.NULL_ARGUMENT);
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
