package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckAsyncOnCommonPoolRule extends AbstractRule {
    private static final String COMPLETABLE_FUTURE = "CompletableFuture";
    private static final Set<String> ASYNC_METHODS = Set.of("supplyAsync", "runAsync");

    @Override
    public String code() {
        return RuleCodes.CHECK_ASYNC_ON_COMMON_POOL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет CompletableFuture.supplyAsync / runAsync без собственного пула потоков";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Второй аргумент - это как раз пул, в котором выполнится задача
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> ASYNC_METHODS.contains(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> MethodCalls.isCallOn(call, COMPLETABLE_FUTURE, call.getNameAsString()))
                .filter(call -> !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "CompletableFuture." + call.getNameAsString() + "(...) без пула: задача уйдет в общий"
                                + " ForkJoinPool, в котором потоков по числу ядер и который делят все параллельные"
                                + " стримы приложения - блокирующая задача остановит их все; передайте свой"
                                + " executor вторым аргументом"))
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
