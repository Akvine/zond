package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Set;

@Component
public class CheckFutureWithoutErrorHandlingRule extends AbstractRule {
    private static final Set<String> START_METHODS = Set.of("supplyAsync", "runAsync");

    // Операции, после которых ошибка не потеряется: ее обработают либо получат вместе с результатом
    private static final Set<String> HANDLING_METHODS =
            Set.of("exceptionally", "exceptionallyCompose", "handle", "handleAsync", "whenComplete", "join", "get");

    @Override
    public String code() {
        return RuleCodes.CHECK_FUTURE_WITHOUT_ERROR_HANDLING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет CompletableFuture, запущенный без обработки ошибки и без ожидания результата";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> START_METHODS.contains(call.getNameAsString()))
                .filter(call -> isDiscardedWithoutHandling(call) && !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "CompletableFuture." + call.getNameAsString() + "(...) запущен, а результат никому не нужен:"
                                + " если задача упадет, исключение исчезнет без следа - ни в логе, ни в ответе;"
                                + " добавьте exceptionally(...) или whenComplete(...)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }

    // Цепочка стоит отдельным оператором, и в ней нет ни обработки ошибки, ни ожидания
    private boolean isDiscardedWithoutHandling(MethodCallExpr start) {
        MethodCallExpr top = start;
        while (true) {
            MethodCallExpr current = top;
            if (HANDLING_METHODS.contains(current.getNameAsString())) {
                return false;
            }
            var next = current.getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr)
                    .map(parent -> (MethodCallExpr) parent)
                    .filter(parent -> parent.getScope().filter(scope -> scope == current).isPresent());
            if (next.isEmpty()) {
                break;
            }
            top = next.get();
        }
        return top.getParentNode().filter(parent -> parent instanceof ExpressionStmt).isPresent();
    }
}
