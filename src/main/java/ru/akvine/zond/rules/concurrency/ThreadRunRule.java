package ru.akvine.zond.rules.concurrency;

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
public class ThreadRunRule extends AbstractRule {
    private static final String THREAD = "Thread";
    private static final String RUN = "run";

    @Override
    public String code() {
        return RuleCodes.THREAD_RUN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызов Thread.run() вместо Thread.start()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // thread.run() на переменной типа Thread либо new Thread(...).run()
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> RUN.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(call -> call.getScope().flatMap(LocalTypes::typeOf).filter(THREAD::equals).isPresent())
                .map(call -> violation(sourceFile, call,
                        "Вызов '" + call + "': run() выполнит код в текущем потоке, новый поток не запустится;"
                                + " используйте start()"))
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
}
