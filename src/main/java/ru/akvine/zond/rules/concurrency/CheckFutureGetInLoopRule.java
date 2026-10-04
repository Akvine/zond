package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckFutureGetInLoopRule extends AbstractRule {
    private static final Set<String> WAIT_METHODS = Set.of("get", "join");

    // Методы, которые запускают задачу и возвращают Future
    private static final Set<String> ASYNC_STARTS = Set.of("submit", "supplyAsync", "runAsync", "schedule");

    @Override
    public String code() {
        return RuleCodes.CHECK_FUTURE_GET_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запуск задачи и ожидание ее результата на одной и той же итерации цикла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!WAIT_METHODS.contains(call.getNameAsString()) || call.getScope().isEmpty()) {
                continue;
            }

            // Сначала запустить все задачи, а потом в цикле собрать результаты - нормально.
            // Ошибка - когда задача запускается и тут же ожидается на той же итерации
            Optional<Node> iteration = Loops.enclosingIteration(call);
            if (iteration.isPresent() && isStartedInSameIteration(call.getScope().get(), iteration.get())) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' в цикле сразу после запуска задачи: следующая задача не стартует, пока не"
                                + " завершится текущая, параллельная обработка превращается в последовательную;"
                                + " сначала запустите все задачи, затем соберите результаты"));
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

    private boolean isStartedInSameIteration(Expression scope, Node iteration) {
        Expression value = Nodes.unwrap(scope);

        // executor.submit(task).get()
        if (isAsyncStart(value)) {
            return true;
        }

        // Future<?> future = executor.submit(task); future.get(); - переменная объявлена внутри цикла
        return LocalTypes.findDeclaration(value)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .filter(declaration -> !Loops.isDeclaredOutside(iteration, declaration))
                .flatMap(declaration -> ((VariableDeclarator) declaration).getInitializer())
                .filter(this::isAsyncStart)
                .isPresent();
    }

    private boolean isAsyncStart(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value.isMethodCallExpr() && ASYNC_STARTS.contains(value.asMethodCallExpr().getNameAsString());
    }
}
