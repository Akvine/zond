package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckConditionAwaitOutsideLoopRule extends AbstractRule {
    private static final String CONDITION = "Condition";
    private static final Set<String> AWAIT_METHODS =
            Set.of("await", "awaitNanos", "awaitUntil", "awaitUninterruptibly");

    @Override
    public String code() {
        return RuleCodes.CHECK_CONDITION_AWAIT_OUTSIDE_LOOP_RULE_CODE;
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

    // По типу переменной, чтобы не спутать с CountDownLatch.await(); если тип по файлу не определить - по имени
    private boolean isCondition(Expression scope) {
        return LocalTypes.typeOf(scope)
                .map(CONDITION::equals)
                .orElseGet(() -> scope.toString().toLowerCase().contains(CONDITION.toLowerCase()));
    }
}
