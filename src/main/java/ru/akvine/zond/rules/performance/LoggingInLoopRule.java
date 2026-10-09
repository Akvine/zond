package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.Loggers;

import java.util.List;
import java.util.Set;

@Component
public class LoggingInLoopRule extends AbstractRule {
    private static final Set<String> PROBLEM_LEVELS = Set.of("warn", "error");

    @Override
    public String code() {
        return RuleCodes.LOGGING_IN_LOOP_RULE_CODE;
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
                // Итерации цикла повторных попыток - это попытки, а не элементы данных: их считаные единицы
                .filter(CodeContexts::isRepeatedOverData)
                .filter(call -> !isErrorReport(call))
                .map(call -> violation(sourceFile, call,
                        "Логирование " + call.getScope().get() + "." + call.getNameAsString() + "(...) в цикле:"
                                + " на большом объеме это тысячи записей, которые забивают лог и тормозят обработку;"
                                + " выведите одну итоговую запись после цикла либо понизьте уровень до debug"))
                .toList();
    }

    // Сообщение об ошибке, а не о ходе работы: запись в catch либо warn / error под условием. Она появляется
    // не на каждом элементе, а когда с элементом что-то не так, и убирать ее из цикла нельзя
    private boolean isErrorReport(MethodCallExpr call) {
        boolean problemLevel = PROBLEM_LEVELS.contains(call.getNameAsString());
        return CodeContexts.isInCatch(call) || problemLevel && CodeContexts.isConditionalInIteration(call);
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
