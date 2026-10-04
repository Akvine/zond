package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.List;

@Component
public class CheckAtomicGetThenSetRule extends AbstractRule {
    private static final String SET = "set";
    private static final String GET = "get";
    private static final String ATOMIC_PREFIX = "Atomic";

    @Override
    public String code() {
        return RuleCodes.CHECK_ATOMIC_GET_THEN_SET_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет set(get() + ...) у Atomic-переменных вместо атомарной операции";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> SET.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getScope().filter(scope -> isAtomic(scope) && readsSameVariable(call, scope)).isPresent())
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': между get() и set() значение может изменить другой поток, и его"
                                + " изменение потеряется; используйте одну атомарную операцию - incrementAndGet(),"
                                + " addAndGet(...), updateAndGet(...)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private boolean isAtomic(Expression scope) {
        return LocalTypes.typeOf(scope).filter(type -> type.startsWith(ATOMIC_PREFIX)).isPresent();
    }

    // counter.set(counter.get() + 1): в аргументе читается та же переменная
    private boolean readsSameVariable(MethodCallExpr set, Expression scope) {
        return set.getArgument(0).findAll(MethodCallExpr.class).stream()
                .anyMatch(read -> GET.equals(read.getNameAsString())
                        && read.getArguments().isEmpty()
                        && read.getScope().filter(readScope -> readScope.toString().equals(scope.toString())).isPresent());
    }
}
