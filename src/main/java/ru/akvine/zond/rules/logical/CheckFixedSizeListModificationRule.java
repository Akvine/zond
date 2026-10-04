package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractUnmodifiableCollectionRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.Set;

@Component
public class CheckFixedSizeListModificationRule extends AbstractUnmodifiableCollectionRule {
    private static final String ARRAYS = "Arrays";
    private static final String AS_LIST = "asList";

    @Override
    public String code() {
        return RuleCodes.CHECK_FIXED_SIZE_LIST_MODIFICATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет изменение размера списка, созданного через Arrays.asList()";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    @Override
    protected boolean isUnmodifiableSource(MethodCallExpr call) {
        return MethodCalls.isCallOn(call, ARRAYS, AS_LIST);
    }

    @Override
    protected Set<String> modifyingMethods() {
        // set(...) сюда не входит: заменять элементы в таком списке можно
        return Set.of("add", "addAll", "remove", "removeAll", "removeIf", "retainAll", "clear");
    }

    @Override
    protected String message(MethodCallExpr call, String source) {
        return "'" + call.getNameAsString() + "(...)' на списке из " + source + ": это обертка над массивом"
                + " фиксированного размера, вызов закончится UnsupportedOperationException;"
                + " оберните в new ArrayList<>(...)";
    }
}
