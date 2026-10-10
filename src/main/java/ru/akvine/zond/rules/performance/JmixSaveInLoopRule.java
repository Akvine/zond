package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.Expression;
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
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class JmixSaveInLoopRule extends AbstractRule {
    private static final Set<String> WRITE_METHODS = Set.of("save", "remove");
    private static final String SAVE_CONTEXT = "SaveContext";
    private static final Pattern SAVE_CONTEXT_NAME = Pattern.compile("(?i).*savecontext.*|^context$");

    @Override
    public String code() {
        return RuleCodes.JMIX_SAVE_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сохранение и удаление через DataManager на каждом шаге цикла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> Jmix.isDataManagerCall(call, WRITE_METHODS) && !savesContext(call))
                .filter(CodeContexts::isRepeatedOverData)
                .filter(call -> !CodeContexts.isStartup(call) && !Jmix.isBatchOrSingleStep(call))
                .map(call -> violation(sourceFile, call,
                        "dataManager." + call.getNameAsString() + "(...) на каждом шаге цикла: каждый вызов - отдельная"
                                + " транзакция и отдельные запросы в базу; соберите изменения в SaveContext"
                                + " и сохраните одним вызовом после цикла"))
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

    // Сохранение готовой пачки изменений в цикле - это уже разбиение на части, а не запись по одной
    private boolean savesContext(MethodCallExpr call) {
        if (call.getArguments().size() != 1) {
            return false;
        }
        Expression argument = call.getArgument(0);
        return LocalTypes.typeOf(argument).filter(SAVE_CONTEXT::equals).isPresent()
                || SAVE_CONTEXT_NAME.matcher(argument.toString()).matches()
                || argument.toString().contains(SAVE_CONTEXT);
    }
}
