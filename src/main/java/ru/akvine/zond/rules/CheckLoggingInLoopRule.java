package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckLoggingInLoopRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_LOGGING_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет логирование уровня info и выше внутри циклов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // debug и trace не трогаем: в рабочей среде они обычно выключены
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(Loggers::isLogCall)
                .filter(Loggers::isEnabledLevel)
                .filter(Loops::isRepeated)
                .map(call -> violation(sourceFile, call,
                        "Логирование " + call.getScope().get() + "." + call.getNameAsString() + "(...) в цикле:"
                                + " на большом объеме это тысячи записей, которые забивают лог и тормозят обработку;"
                                + " выведите одну итоговую запись после цикла либо понизьте уровень до debug"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
