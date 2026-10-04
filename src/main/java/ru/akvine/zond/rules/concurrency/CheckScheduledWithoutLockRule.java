package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.List;
import java.util.Set;

@Component
public class CheckScheduledWithoutLockRule extends AbstractRule {
    private static final Set<String> SCHEDULED_ANNOTATIONS = Set.of("Scheduled", "Schedules");

    // ShedLock: блокировка, общая для всех экземпляров приложения
    private static final String SCHEDULER_LOCK = "SchedulerLock";

    // Защита от повторного входа, написанная вручную
    private static final Set<String> GUARD_METHODS = Set.of("tryLock", "compareAndSet", "tryAcquire");

    @Override
    public String code() {
        return RuleCodes.CHECK_SCHEDULED_WITHOUT_LOCK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Scheduled-методы без защиты от одновременного запуска";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.hasAny(method, SCHEDULED_ANNOTATIONS))
                .filter(method -> !Annotations.has(method, SCHEDULER_LOCK))
                .filter(method -> !method.isSynchronized() && !hasManualGuard(method))
                .map(method -> violation(sourceFile, method,
                        "@Scheduled-метод '" + method.getNameAsString() + "' без контроля одновременного запуска:"
                                + " при нескольких экземплярах приложения задача выполнится на каждом из них,"
                                + " а вместе с @Async - еще и параллельно сама с собой; добавьте распределенную"
                                + " блокировку (например, @SchedulerLock из ShedLock)"))
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

    private boolean hasManualGuard(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> GUARD_METHODS.contains(call.getNameAsString()));
    }
}
