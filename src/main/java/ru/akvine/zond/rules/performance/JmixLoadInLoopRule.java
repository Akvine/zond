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
import ru.akvine.zond.rules.support.Jmix;

import java.util.List;

@Component
public class JmixLoadInLoopRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.JMIX_LOAD_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет загрузку через DataManager на каждом шаге цикла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> Jmix.loadChain(call).isPresent())
                // Цикл повторных попыток и постраничный обход обращаются к базе намеренно
                .filter(CodeContexts::isRepeatedOverData)
                // Разовая загрузка при запуске - не то, что тормозит работу приложения
                .filter(call -> !CodeContexts.isStartup(call) && !Jmix.isBatchOrSingleStep(call))
                .map(call -> violation(sourceFile, call,
                        "Загрузка через DataManager на каждом шаге цикла: на каждый элемент уходит отдельный запрос"
                                + " в базу; загрузите нужные записи одним запросом до цикла (условие по списку"
                                + " идентификаторов) и разберите их по ключу"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
