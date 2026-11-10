package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.FailureHandling;
import ru.akvine.zond.rules.support.PessimisticLocks;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Пессимистическая блокировка строки без готовности к отказу. Пока строку держит другая транзакция, запрос ждет,
 * и ожидание заканчивается исключением: по таймауту, при взаимной блокировке, сразу при NOWAIT. Если его никто
 * не перехватывает и операцию не повторяет, запрос пользователя завершается ошибкой 500.
 */
@Component
public class PessimisticLockWithoutHandlingRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3,
            "На сколько вызовов вверх от блокировки искать перехват исключения или повтор; 0 - только в самом методе");

    @Override
    public String code() {
        return RuleCodes.PESSIMISTIC_LOCK_WITHOUT_HANDLING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пессимистическую блокировку строки (@Lock(PESSIMISTIC_WRITE), FOR UPDATE,"
                + " entityManager.lock) без перехвата LockTimeoutException / PessimisticLockException и без повтора";
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        FailureHandling handling = FailureHandling.of(sourceFiles);
        // Обработчик на все приложение отвечает клиенту, где бы блокировка ни сорвалась
        if (handling.hasGlobalHandler(FailureHandling.LOCK_FAILURES)) {
            return List.of();
        }
        PessimisticLocks locks = PessimisticLocks.of(ProjectClasses.of(sourceFiles));
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                Optional<String> lock = locks.describe(call);
                if (lock.isEmpty() || TestClasses.isInside(call)
                        || !handling.escapes(call, FailureHandling.LOCK_FAILURES, value(MAX_CALL_DEPTH))) {
                    continue;
                }
                violations.add(violation(sourceFile, call,
                        "Блокировка строки (" + lock.get() + ") берется без готовности к отказу: если строку"
                                + " держит другая транзакция, ожидание закончится исключением -"
                                + " LockTimeoutException или PessimisticLockException (в Spring -"
                                + " CannotAcquireLockException, PessimisticLockingFailureException), - и запрос"
                                + " завершится ошибкой 500; перехватите исключение, повторите операцию"
                                + " (@Retryable, RetryTemplate) либо заведите для него @ExceptionHandler"));
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
        return ErrorType.EXCEPTION;
    }
}
