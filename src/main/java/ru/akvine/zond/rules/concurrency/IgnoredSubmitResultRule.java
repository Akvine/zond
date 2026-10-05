package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class IgnoredSubmitResultRule extends AbstractRule {
    private static final String SUBMIT = "submit";

    // submit(task) и submit(task, result)
    private static final int SUBMIT_MAX_ARGUMENTS = 2;

    private static final Set<String> EXECUTOR_TYPES = Set.of(
            "ExecutorService", "ScheduledExecutorService", "ThreadPoolExecutor", "ScheduledThreadPoolExecutor",
            "ForkJoinPool", "ThreadPoolTaskExecutor", "AsyncTaskExecutor", "CompletionService",
            "ExecutorCompletionService");

    private static final Pattern EXECUTOR_NAME =
            Pattern.compile(".*(executor|pool|scheduler).*", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.IGNORED_SUBMIT_RESULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет submit(task), у которого возвращенный Future не используется";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ExpressionStmt statement : sourceFile.unit().findAll(ExpressionStmt.class)) {
            // () -> executor.submit(task): тело лямбды-выражения хранится как оператор, но его результат возвращается
            boolean isLambdaBody = statement.getParentNode().filter(parent -> parent instanceof LambdaExpr).isPresent();
            if (isLambdaBody || !statement.getExpression().isMethodCallExpr()) {
                continue;
            }

            MethodCallExpr call = statement.getExpression().asMethodCallExpr();
            boolean isSubmit = SUBMIT.equals(call.getNameAsString())
                    && !call.getArguments().isEmpty()
                    && call.getArguments().size() <= SUBMIT_MAX_ARGUMENTS;

            if (isSubmit && call.getScope().filter(this::isExecutor).isPresent()) {
                violations.add(violation(sourceFile, call,
                        "Future из '" + call.getScope().get() + ".submit(...)' не используется: исключение"
                                + " задачи останется внутри Future и нигде не появится; сохраните Future"
                                + " и вызовите get(), либо используйте execute() с обработкой ошибок в задаче"));
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

    // По типу; если тип определить не удалось - по имени: executor, threadPool, getScheduler()
    private boolean isExecutor(Expression scope) {
        return LocalTypes.isAnyOf(scope, EXECUTOR_TYPES, () -> EXECUTOR_NAME.matcher(lastName(scope)).matches());
    }

    private String lastName(Expression scope) {
        if (scope.isMethodCallExpr()) {
            return scope.asMethodCallExpr().getNameAsString();
        }
        if (scope.isFieldAccessExpr()) {
            return scope.asFieldAccessExpr().getNameAsString();
        }
        return scope.toString();
    }
}
