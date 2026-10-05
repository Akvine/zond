package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;

@Component
public class WaitNotifyRule extends AbstractRule {
    private static final String WAIT = "wait";
    private static final String NOTIFY = "notify";

    // wait(), wait(timeout), wait(timeout, nanos)
    private static final int WAIT_MAX_ARGUMENTS = 2;

    @Override
    public String code() {
        return RuleCodes.WAIT_NOTIFY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет wait() вне цикла и notify() вместо notifyAll()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (WAIT.equals(call.getNameAsString())
                    && call.getArguments().size() <= WAIT_MAX_ARGUMENTS
                    && Nodes.enclosingLoop(call).isEmpty()) {
                violations.add(violation(sourceFile, call,
                        "wait() вне цикла: поток может проснуться без notify (spurious wakeup) либо когда условие"
                                + " уже снова не выполняется; вызывайте wait() в цикле while с проверкой условия"));
            }

            if (NOTIFY.equals(call.getNameAsString()) && call.getArguments().isEmpty()) {
                violations.add(violation(sourceFile, call,
                        "notify() будит один произвольный поток: если он ждет другого условия, сигнал потеряется"
                                + " и остальные потоки зависнут; используйте notifyAll()"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }
}
