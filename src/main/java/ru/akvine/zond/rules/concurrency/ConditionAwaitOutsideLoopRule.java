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
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Set;

@Component
public class ConditionAwaitOutsideLoopRule extends AbstractRule {
    private static final String CONDITION = "Condition";
    private static final Set<String> AWAIT_METHODS =
            Set.of("await", "awaitNanos", "awaitUntil", "awaitUninterruptibly");

    @Override
    public String code() {
        return RuleCodes.CONDITION_AWAIT_OUTSIDE_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Condition.await() вне цикла с проверкой условия";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> AWAIT_METHODS.contains(call.getNameAsString()))
                .filter(call -> call.getScope().filter(this::isCondition).isPresent())
                .filter(call -> Nodes.enclosingLoop(call).isEmpty())
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' вне цикла: поток может проснуться без сигнала (spurious wakeup) либо когда"
                                + " условие уже снова не выполняется; вызывайте await() в цикле while"
                                + " с проверкой условия"))
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

    // По типу, чтобы не спутать с CountDownLatch.await(); если тип определить не удалось - по имени
    private boolean isCondition(Expression scope) {
        return LocalTypes.isAnyOf(scope, Set.of(CONDITION),
                () -> scope.toString().toLowerCase().contains(CONDITION.toLowerCase()));
    }
}
