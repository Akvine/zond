package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;

@Component
public class SleepInLoopRule extends AbstractRule {
    private static final String THREAD = "Thread";
    private static final String SLEEP = "sleep";

    @Override
    public String code() {
        return RuleCodes.SLEEP_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ожидание через Thread.sleep в цикле";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // В тестах sleep ловит отдельное правило
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> MethodCalls.isCallOn(call, THREAD, SLEEP))
                .filter(call -> Nodes.enclosingLoop(call).isPresent() && !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "Thread.sleep(...) в цикле: поток занят ожиданием и просыпается по расписанию, а не когда"
                                + " событие наступило; используйте CountDownLatch, BlockingQueue,"
                                + " CompletableFuture либо планировщик"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }
}
