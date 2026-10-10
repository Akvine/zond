package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class LostStackTraceInLogRule extends AbstractRule {
    private static final String INTERRUPTED = "InterruptedException";
    // Вместо самого исключения в лог попадает только его текст
    private static final Set<String> TEXT_METHODS = Set.of("getMessage", "getLocalizedMessage", "toString");

    @Override
    public String code() {
        return RuleCodes.LOST_STACK_TRACE_IN_LOG_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет в catch запись в лог текста исключения без самого исключения";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (CatchClause clause : sourceFile.unit().findAll(CatchClause.class)) {
            String caught = clause.getParameter().getNameAsString();
            // Исключение выбрасывается дальше - стек уходит с ним и будет записан там, где его обработают.
            // У InterruptedException стек ничего не говорит: это просьба остановиться, а не ошибка
            if (CodeContexts.rethrowsWithCause(clause)
                    || INTERRUPTED.equals(clause.getParameter().getType().asString())) {
                continue;
            }
            for (MethodCallExpr call : clause.getBody().findAll(MethodCallExpr.class)) {
                if (Loggers.isLogCall(call)
                        && Loggers.isEnabledLevel(call)
                        && logsOnlyText(call, caught)
                        && !passesException(call, caught)) {
                    violations.add(violation(sourceFile, call,
                            "В лог пишется только текст исключения '" + caught + "', без стека: по записи не"
                                    + " понять, где возникла ошибка, а у NullPointerException текста может не быть"
                                    + " вовсе; передайте исключение последним аргументом: log.error(\"...\", "
                                    + caught + ")"));
                }
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

    // e.getMessage() где-либо в аргументах
    private boolean logsOnlyText(MethodCallExpr logCall, String caught) {
        return logCall.getArguments().stream()
                .flatMap(argument -> argument.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> TEXT_METHODS.contains(call.getNameAsString())
                        && call.getScope().filter(scope -> scope.toString().equals(caught)).isPresent());
    }

    // Само исключение передано отдельным аргументом
    private boolean passesException(MethodCallExpr logCall, String caught) {
        return logCall.getArguments().stream()
                .map(Nodes::unwrap)
                .anyMatch(argument -> argument.isNameExpr() && argument.asNameExpr().getNameAsString().equals(caught));
    }
}
