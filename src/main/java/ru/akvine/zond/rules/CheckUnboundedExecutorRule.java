package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class CheckUnboundedExecutorRule extends AbstractRule {
    private static final String EXECUTORS = "Executors";
    private static final String THREAD_POOL_EXECUTOR = "ThreadPoolExecutor";
    private static final String MAX_VALUE = "Integer.MAX_VALUE";

    // Создают поток на каждую задачу, если свободных нет
    private static final Set<String> UNBOUNDED_THREADS_FACTORIES = Set.of("newCachedThreadPool");

    // Число потоков ограничено, но очередь задач - LinkedBlockingQueue без предела
    private static final Set<String> UNBOUNDED_QUEUE_FACTORIES = Set.of(
            "newFixedThreadPool", "newSingleThreadExecutor", "newScheduledThreadPool",
            "newSingleThreadScheduledExecutor");

    private static final Set<String> UNBOUNDED_QUEUES = Set.of("LinkedBlockingQueue", "LinkedBlockingDeque");

    @Override
    public String code() {
        return RuleCodes.CHECK_UNBOUNDED_EXECUTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пулы потоков без ограничения числа потоков или размера очереди";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!MethodCalls.isCallOn(call, EXECUTORS, name)) {
                continue;
            }
            if (UNBOUNDED_THREADS_FACTORIES.contains(name)) {
                violations.add(violation(sourceFile, call,
                        "Executors." + name + "(): число потоков не ограничено, под нагрузкой пул создаст их"
                                + " столько, сколько придет задач; используйте ThreadPoolExecutor с явными пределами"));
            }
            if (UNBOUNDED_QUEUE_FACTORIES.contains(name)) {
                violations.add(violation(sourceFile, call,
                        "Executors." + name + "(...): очередь задач не ограничена, при медленной обработке задачи"
                                + " копятся до OutOfMemoryError; используйте ThreadPoolExecutor с ограниченной"
                                + " очередью и политикой отказа"));
            }
        }

        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (THREAD_POOL_EXECUTOR.equals(creation.getType().getNameAsString())
                    && creation.getArguments().stream().anyMatch(this::isUnbounded)) {
                violations.add(violation(sourceFile, creation,
                        "new ThreadPoolExecutor(...) без предела: очередь без емкости либо Integer.MAX_VALUE в"
                                + " параметрах пула; задайте конечный размер очереди и число потоков"));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    // new LinkedBlockingQueue<>() без емкости либо Integer.MAX_VALUE
    private boolean isUnbounded(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isObjectCreationExpr()) {
            return UNBOUNDED_QUEUES.contains(value.asObjectCreationExpr().getType().getNameAsString())
                    && value.asObjectCreationExpr().getArguments().isEmpty();
        }
        return MAX_VALUE.equals(value.toString());
    }
}
