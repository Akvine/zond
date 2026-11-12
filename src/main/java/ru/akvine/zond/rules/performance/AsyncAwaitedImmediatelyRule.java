package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code CompletableFuture.supplyAsync(() -> load(id)).join()}: задачу отдали другому потоку и тут же встали ее
 * ждать. Параллельно ничего не выполняется, а к работе добавились переключение потоков и занятый поток пула.
 * То же в цикле показывает отдельное правило.
 */
@Component
public class AsyncAwaitedImmediatelyRule extends AbstractRule {
    // Ожидание с таймаутом - способ ограничить время работы, это осознанный прием
    private static final Set<String> WAITS = Set.of("get", "join");
    private static final Set<String> STARTS = Set.of("supplyAsync", "runAsync", "submit");
    // pool.submit(() -> items.parallelStream()...).get(): так параллельный стрим запускают в своем пуле
    private static final String PARALLEL = "parallel";

    @Override
    public String code() {
        return RuleCodes.ASYNC_AWAITED_IMMEDIATELY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет задачи, которые запускают в другом потоке и тут же ждут их результата:"
                + " supplyAsync(...).join(), submit(...).get()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr wait : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!WAITS.contains(wait.getNameAsString()) || !wait.getArguments().isEmpty()
                    || wait.getScope().isEmpty() || Loops.isRepeated(wait) || TestClasses.isInside(wait)) {
                continue;
            }
            findStart(wait).ifPresent(start -> violations.add(violation(sourceFile, wait.getName(),
                    "Задачу '" + start.getNameAsString() + "(...)' запускают в другом потоке и тут же ждут через '"
                            + wait.getNameAsString() + "()': вызывающий поток все это время стоит, параллельно ничего"
                            + " не выполняется, а к работе добавились переключение потоков и занятый поток пула;"
                            + " выполните код напрямую либо запустите несколько задач и дождитесь их вместе")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Иногда так уходят из текущей транзакции или потока с его контекстом - это видно только по смыслу
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    private Optional<MethodCallExpr> findStart(MethodCallExpr wait) {
        Expression scope = Nodes.unwrap(wait.getScope().get());
        if (scope.isMethodCallExpr()) {
            return Optional.of(scope.asMethodCallExpr()).filter(this::isStart);
        }
        if (!scope.isNameExpr()) {
            return Optional.empty();
        }
        // Задачу положили в переменную и следующим же оператором ждут ее результата
        return LocalTypes.findDeclaration(scope)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .map(declaration -> (VariableDeclarator) declaration)
                .filter(variable -> isAwaitedNext(variable, wait) && isUsedOnce(scope.asNameExpr()))
                .flatMap(VariableDeclarator::getInitializer)
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(this::isStart);
    }

    private boolean isStart(MethodCallExpr call) {
        return STARTS.contains(call.getNameAsString()) && call.getScope().isPresent()
                && !call.toString().contains(PARALLEL);
    }

    // Между запуском и ожиданием нет ни одного оператора: вызывающий поток ничем другим не занят
    private boolean isAwaitedNext(VariableDeclarator variable, MethodCallExpr wait) {
        Optional<Statement> declaration = variable.findAncestor(Statement.class);
        Optional<BlockStmt> block = declaration.flatMap(statement -> statement.getParentNode())
                .filter(parent -> parent instanceof BlockStmt)
                .map(parent -> (BlockStmt) parent);
        if (block.isEmpty()) {
            return false;
        }
        int index = block.get().getStatements().indexOf(declaration.get());
        if (index + 1 >= block.get().getStatements().size()) {
            return false;
        }
        Node next = block.get().getStatement(index + 1);
        return next == wait || Nodes.isInside(wait, next);
    }

    private boolean isUsedOnce(NameExpr variable) {
        return variable.findAncestor(CallableDeclaration.class)
                .filter(callable -> callable.findAll(NameExpr.class).stream()
                        .filter(usage -> usage.getNameAsString().equals(variable.getNameAsString()))
                        .count() == 1)
                .isPresent();
    }
}
