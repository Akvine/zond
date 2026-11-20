package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class IgnoredSubmitResultRule extends AbstractRule {
    private static final String SUBMIT = "submit";
    private static final Set<String> BROAD = Set.of("Exception", "Throwable");

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
        for (ExpressionStmt statement : sourceFile.findAll(ExpressionStmt.class)) {
            // () -> executor.submit(task): тело лямбды-выражения хранится как оператор, но его результат возвращается
            boolean isLambdaBody = statement.getParentNode().filter(parent -> parent instanceof LambdaExpr).isPresent();
            if (isLambdaBody || !statement.getExpression().isMethodCallExpr()) {
                continue;
            }

            MethodCallExpr call = statement.getExpression().asMethodCallExpr();
            boolean isSubmit = SUBMIT.equals(call.getNameAsString())
                    && !call.getArguments().isEmpty()
                    && call.getArguments().size() <= SUBMIT_MAX_ARGUMENTS;

            if (isSubmit && call.getScope().filter(this::isExecutor).isPresent() && !catchesOwnErrors(call.getArgument(0))) {
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

    // submit(() -> { log.trace(...); try { ... } catch (Exception e) { log.error(..., e); } }): задача сама ловит
    // любую ошибку, и в Future ей попасть не из чего
    private boolean catchesOwnErrors(Expression task) {
        return bodyOf(task).filter(this::catchesEverythingIn).isPresent();
    }

    // Тело задачи: блок лямбды либо свой метод, который она вызывает - submit(() -> send(host, key)),
    // submit(this::watchConfigFile)
    private Optional<List<Statement>> bodyOf(Expression task) {
        if (task instanceof LambdaExpr lambda && lambda.getBody().isBlockStmt()) {
            return Optional.of(lambda.getBody().asBlockStmt().getStatements());
        }
        String name = null;
        int arguments = -1;
        if (task instanceof LambdaExpr lambda
                && lambda.getExpressionBody().filter(Expression::isMethodCallExpr).isPresent()) {
            MethodCallExpr call = lambda.getExpressionBody().get().asMethodCallExpr();
            if (call.getScope().filter(scope -> !scope.isThisExpr()).isEmpty()) {
                name = call.getNameAsString();
                arguments = call.getArguments().size();
            }
        } else if (task instanceof MethodReferenceExpr reference && reference.getScope().isThisExpr()) {
            name = reference.getIdentifier();
        }
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name;
        int count = arguments;
        List<MethodDeclaration> own = task.findAncestor(TypeDeclaration.class).stream()
                .flatMap(type -> ((TypeDeclaration<?>) type).getMethodsByName(wanted).stream())
                .filter(method -> count < 0 || method.getParameters().size() == count)
                .toList();
        return own.size() == 1
                ? own.get(0).getBody().map(body -> body.getStatements())
                : Optional.empty();
    }

    private boolean catchesEverythingIn(List<Statement> statements) {
        boolean guarded = statements.stream().anyMatch(this::isGuarded);
        return guarded && statements.stream().allMatch(statement -> isGuarded(statement)
                || statement.isExpressionStmt() && statement.asExpressionStmt().getExpression().isMethodCallExpr()
                && Loggers.isLogCall(statement.asExpressionStmt().getExpression().asMethodCallExpr()));
    }

    // try с catch на любую ошибку либо рабочий цикл, каждый проход которого завернут в такой try:
    // while (!stopped) { try { ... } catch (Exception e) { log.error(..., e); } }
    private boolean isGuarded(Statement statement) {
        if (catchesEverything(statement)) {
            return true;
        }
        Statement body = null;
        if (statement.isWhileStmt()) {
            body = statement.asWhileStmt().getBody();
        } else if (statement.isDoStmt()) {
            body = statement.asDoStmt().getBody();
        } else if (statement.isForStmt()) {
            body = statement.asForStmt().getBody();
        } else if (statement.isForEachStmt()) {
            body = statement.asForEachStmt().getBody();
        }
        return body != null && body.isBlockStmt() && catchesEverythingIn(body.asBlockStmt().getStatements());
    }

    private boolean catchesEverything(Statement statement) {
        return statement.isTryStmt() && statement.asTryStmt().getCatchClauses().stream()
                .anyMatch(clause -> BROAD.contains(LocalTypes.typeName(clause.getParameter().getType())));
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
