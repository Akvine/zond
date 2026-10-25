package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * executor.scheduleAtFixedRate(() -> sync(), 0, 1, MINUTES): если задача хоть раз бросит исключение,
 * ScheduledExecutorService молча отменит все следующие запуски - ни записи в логе, ни ошибки. Timer.schedule с
 * TimerTask и вовсе останавливает поток таймера вместе со всеми его задачами. TaskScheduler из Spring ошибки
 * сам ловит и пишет в лог, поэтому он не проверяется
 */
@Component
public class PeriodicTaskWithoutCatchRule extends AbstractRule {
    private static final Set<String> EXECUTOR_METHODS = Set.of("scheduleAtFixedRate", "scheduleWithFixedDelay");
    // command, initialDelay, period, unit - так их принимает ScheduledExecutorService, а не TaskScheduler
    private static final int EXECUTOR_ARGUMENTS = 4;
    private static final Set<String> TIMER_METHODS = Set.of("schedule", "scheduleAtFixedRate");
    // task, delay, period: у Timer повтор задает третий аргумент
    private static final int TIMER_ARGUMENTS = 3;
    private static final String TIMER_TASK = "TimerTask";
    private static final String RUN = "run";
    private static final Set<String> BROAD_EXCEPTIONS = Set.of("Exception", "Throwable", "RuntimeException");
    private static final Set<String> LOCKS = Set.of("lock", "lockInterruptibly");
    // Сколько своих методов можно пройти, проверяя, что вызов защищен: задача -> sync() -> doSync()
    private static final int MAX_DELEGATION_DEPTH = 3;

    @Override
    public String code() {
        return RuleCodes.PERIODIC_TASK_WITHOUT_CATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет периодическую задачу ScheduledExecutorService и Timer без перехвата исключений:"
                + " первое же исключение молча отменяет все следующие запуски";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> !TestClasses.isInside(call))
                .filter(call -> taskOf(call).filter(task -> !catchesEverything(task, call)).isPresent())
                .map(call -> violation(sourceFile, call,
                        "Периодическая задача '" + call.getNameAsString() + "' не перехватывает исключения: первое"
                                + " же исключение молча отменит все следующие запуски - без записи в логе и без"
                                + " ошибки; оберните тело задачи в try / catch (Exception) с записью в лог"))
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

    private Optional<Expression> taskOf(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (EXECUTOR_METHODS.contains(method) && call.getArguments().size() == EXECUTOR_ARGUMENTS) {
            return Optional.of(call.getArgument(0));
        }
        boolean timerTask = TIMER_METHODS.contains(method) && call.getArguments().size() == TIMER_ARGUMENTS
                && call.getArgument(0) instanceof ObjectCreationExpr creation
                && TIMER_TASK.equals(creation.getType().getNameAsString());
        return timerTask ? Optional.of(call.getArgument(0)) : Optional.empty();
    }

    // Тело задачи целиком внутри try с catch (Exception) либо шире; this::sync и () -> sync() - то же у sync
    private boolean catchesEverything(Expression task, MethodCallExpr call) {
        Optional<List<Statement>> body = bodyOf(task, call);
        if (body.isEmpty()) {
            // Задача - переменная или метод другого класса: ее тела не видно
            return true;
        }
        return isGuarded(body.get(), 0);
    }

    private boolean isGuarded(List<Statement> body, int depth) {
        return !body.isEmpty() && body.stream().allMatch(statement -> isGuarded(statement, depth));
    }

    // try с catch (Exception); if (!enabled) return; цикл, каждая итерация которого внутри такого try;
    // объявление переменной без вызовов; lock() и запись в лог; вызов своего метода, тело которого защищено
    private boolean isGuarded(Statement statement, int depth) {
        if (statement instanceof TryStmt attempt) {
            return attempt.getCatchClauses().stream()
                    .map(CatchClause::getParameter)
                    .flatMap(parameter -> caught(parameter.getType()))
                    .anyMatch(BROAD_EXCEPTIONS::contains);
        }
        if (statement instanceof IfStmt branch) {
            return branch.getElseStmt().isEmpty() && isEarlyExit(branch.getThenStmt());
        }
        if (statement instanceof BlockStmt block) {
            return isGuarded(block.getStatements(), depth);
        }
        if (statement instanceof ForEachStmt loop) {
            return isGuarded(loop.getBody(), depth);
        }
        if (statement instanceof ForStmt loop) {
            return isGuarded(loop.getBody(), depth);
        }
        if (!statement.isExpressionStmt()) {
            return false;
        }
        Expression expression = statement.asExpressionStmt().getExpression();
        if (expression instanceof MethodCallExpr call) {
            return isHarmless(call) || isGuardedOwnCall(call, depth);
        }
        return expression.isVariableDeclarationExpr() && statement.findAll(MethodCallExpr.class).isEmpty();
    }

    // updateLock.lock() перед try, log.info("tick") - не бросают
    private boolean isHarmless(MethodCallExpr call) {
        return LOCKS.contains(call.getNameAsString()) && call.getArguments().isEmpty()
                || Loggers.isLogCall(call) && call.getArguments().stream()
                .allMatch(argument -> argument.findAll(MethodCallExpr.class).isEmpty());
    }

    // sync() или this.sync(), если тело sync своего класса тоже защищено
    private boolean isGuardedOwnCall(MethodCallExpr call, int depth) {
        boolean own = call.getScope().isEmpty() || call.getScope().get().isThisExpr();
        return own && depth < MAX_DELEGATION_DEPTH
                && ownMethod(call, call.getNameAsString(), call.getArguments().size())
                .filter(body -> isGuarded(body, depth + 1))
                .isPresent();
    }

    private boolean isEarlyExit(Statement statement) {
        if (statement instanceof BlockStmt block) {
            return block.getStatements().size() == 1 && isEarlyExit(block.getStatement(0));
        }
        return statement.isReturnStmt() || statement.isContinueStmt();
    }

    private Stream<String> caught(Type type) {
        return type.isUnionType()
                ? type.asUnionType().getElements().stream().map(LocalTypes::typeName)
                : Stream.of(LocalTypes.typeName(type));
    }

    private Optional<List<Statement>> bodyOf(Expression task, MethodCallExpr call) {
        if (task instanceof LambdaExpr lambda) {
            return Optional.of(lambda.getBody().isBlockStmt()
                    ? lambda.getBody().asBlockStmt().getStatements()
                    : List.of(lambda.getBody()));
        }
        if (task instanceof MethodReferenceExpr reference && reference.getScope().isThisExpr()) {
            return ownMethod(call, reference.getIdentifier(), 0);
        }
        if (task instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
            return creation.getAnonymousClassBody().get().stream()
                    .filter(member -> member instanceof MethodDeclaration method && RUN.equals(method.getNameAsString()))
                    .map(member -> (MethodDeclaration) member)
                    .flatMap(method -> method.getBody().stream())
                    .map(block -> (List<Statement>) block.getStatements())
                    .findFirst();
        }
        return Optional.empty();
    }

    private Optional<List<Statement>> ownMethod(Node from, String name, int arguments) {
        return from.findAncestor(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(type -> type.getMethodsByName(name).stream())
                .filter(method -> method.getParameters().size() == arguments)
                .flatMap(method -> method.getBody().stream())
                .map(block -> (List<Statement>) block.getStatements())
                .findFirst();
    }
}
