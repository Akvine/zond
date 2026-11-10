package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Настройки очереди пула потоков. Пул создает потоки сверх corePoolSize, только когда очередь заполнена. У
 * ThreadPoolTaskExecutor очередь по умолчанию безразмерная, поэтому maxPoolSize без queueCapacity не работает
 * никогда. А у пула с ограниченной очередью лишняя задача отклоняется исключением - если политика отказа
 * не задана.
 */
@Component
public class ExecutorQueueSettingsRule extends AbstractContextRule {
    private static final String TASK_EXECUTOR = "ThreadPoolTaskExecutor";
    private static final String THREAD_POOL_EXECUTOR = "ThreadPoolExecutor";
    private static final String SET_MAX = "setMaxPoolSize";
    private static final String SET_CORE = "setCorePoolSize";
    private static final String SET_QUEUE = "setQueueCapacity";
    private static final String SET_HANDLER = "setRejectedExecutionHandler";
    private static final String UNBOUNDED = "Integer.MAX_VALUE";
    private static final Set<String> BOUNDED_QUEUES = Set.of("ArrayBlockingQueue", "LinkedBlockingQueue", "LinkedBlockingDeque");
    // Без политики отказа у конструктора пять аргументов либо шесть, если шестой - фабрика потоков
    private static final int WITHOUT_OPTIONS = 5;
    private static final int WITH_FACTORY_AND_HANDLER = 7;
    private static final Set<String> HANDLER_HINTS = Set.of("Policy", "Handler", "->");
    // Отказ обрабатывают сами: ловят исключение у отправителя
    private static final Set<String> REJECTION_HANDLING = Set.of("RejectedExecutionException", "TaskRejectedException");

    private static final String MAX_SIZE_KEY = "spring.task.execution.pool.max-size";
    private static final String QUEUE_KEY = "spring.task.execution.pool.queue-capacity";
    private static final String MAX_IGNORED = ": пул создает потоки сверх corePoolSize, только когда очередь заполнена,"
            + " а очередь по умолчанию безразмерная (Integer.MAX_VALUE) - она не заполнится никогда. Пул так и"
            + " останется на corePoolSize потоков, а задачи будут копиться в памяти; задайте размер очереди: ";

    @Override
    public String code() {
        return RuleCodes.EXECUTOR_QUEUE_SETTINGS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и настройки и ищет maxPoolSize без queueCapacity у ThreadPoolTaskExecutor (он не"
                + " работает) и пулы с ограниченной очередью без политики отказа";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            boolean handlesRejection = handlesRejection(sourceFile);
            for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
                if (TestClasses.isInside(creation)) {
                    continue;
                }
                String type = creation.getType().getNameAsString();
                if (TASK_EXECUTOR.equals(type)) {
                    checkTaskExecutor(sourceFile, creation, handlesRejection, violations);
                }
                if (THREAD_POOL_EXECUTOR.equals(type) && !handlesRejection && isBoundedWithoutHandler(creation)) {
                    violations.add(violation(sourceFile, creation,
                            "new ThreadPoolExecutor(...) с ограниченной очередью без политики отказа: когда очередь"
                                    + " и все потоки заняты, очередная задача отклоняется - execute() и submit()"
                                    + " бросают RejectedExecutionException (политика по умолчанию - AbortPolicy)."
                                    + " Задайте политику явно последним аргументом - CallerRunsPolicy притормозит"
                                    + " отправителя - либо ловите исключение там, где задачу отправляют")
                            .withLevel(ErrorLevel.MINOR));
                }
            }
        }
        for (ConfigFile config : context.configFiles()) {
            Optional<ConfigProperty> maxSize = config.find(MAX_SIZE_KEY);
            if (maxSize.isPresent() && config.find(QUEUE_KEY).isEmpty()) {
                violations.add(violation(config.path(), maxSize.get().line(),
                        MAX_SIZE_KEY + " без " + QUEUE_KEY + MAX_IGNORED + QUEUE_KEY));
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
        return ErrorType.RESOURCE;
    }

    private boolean handlesRejection(SourceFile sourceFile) {
        String text = sourceFile.unit().toString();
        return REJECTION_HANDLING.stream().anyMatch(text::contains);
    }

    // ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor(); executor.setMaxPoolSize(50); ...
    private void checkTaskExecutor(
            SourceFile sourceFile, ObjectCreationExpr creation, boolean handlesRejection, List<Violation> violations) {
        Optional<String> name = creation.getParentNode()
                .filter(parent -> parent instanceof VariableDeclarator)
                .map(parent -> ((VariableDeclarator) parent).getNameAsString());
        Optional<Node> callable = Nodes.enclosingCallable(creation);
        if (name.isEmpty() || callable.isEmpty()) {
            return;
        }
        List<MethodCallExpr> setters = callable.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope().filter(scope -> scope.toString().equals(name.get())).isPresent())
                .toList();
        Optional<MethodCallExpr> max = setter(setters, SET_MAX);
        Optional<MethodCallExpr> queue = setter(setters, SET_QUEUE);
        boolean unboundedQueue = queue.isEmpty() || queue.filter(call -> UNBOUNDED.equals(argument(call))).isPresent();
        // setCorePoolSize(8) и setMaxPoolSize(8): пул постоянного размера, расти ему и не нужно
        boolean grows = max.isPresent() && !setter(setters, SET_CORE).map(this::argument).equals(max.map(this::argument));
        if (grows && unboundedQueue) {
            violations.add(violation(sourceFile, max.get(),
                    "'" + Nodes.text(max.get()) + "' без setQueueCapacity(...)" + MAX_IGNORED
                            + name.get() + ".setQueueCapacity(...)"));
        }
        if (!unboundedQueue && setter(setters, SET_HANDLER).isEmpty() && !handlesRejection) {
            violations.add(violation(sourceFile, queue.get(),
                    "'" + Nodes.text(queue.get()) + "' без setRejectedExecutionHandler(...): когда очередь и все"
                            + " потоки заняты, очередная задача отклоняется - отправитель получает"
                            + " TaskRejectedException (политика по умолчанию - AbortPolicy). Задайте политику явно -"
                            + " CallerRunsPolicy притормозит отправителя - либо ловите исключение там, где задачу"
                            + " отправляют").withLevel(ErrorLevel.MINOR));
        }
    }

    private Optional<MethodCallExpr> setter(List<MethodCallExpr> setters, String name) {
        return setters.stream().filter(call -> name.equals(call.getNameAsString()) && call.getArguments().size() == 1).findFirst();
    }

    private String argument(MethodCallExpr setter) {
        return Nodes.text(Nodes.unwrap(setter.getArgument(0)));
    }

    // new ThreadPoolExecutor(4, 16, 60, SECONDS, new ArrayBlockingQueue<>(100)) - отказ по умолчанию бросает исключение
    private boolean isBoundedWithoutHandler(ObjectCreationExpr creation) {
        List<Expression> arguments = creation.getArguments();
        if (arguments.size() < WITHOUT_OPTIONS || arguments.size() >= WITH_FACTORY_AND_HANDLER) {
            return false;
        }
        Expression queue = Nodes.unwrap(arguments.get(WITHOUT_OPTIONS - 1));
        boolean bounded = queue.isObjectCreationExpr()
                && BOUNDED_QUEUES.contains(queue.asObjectCreationExpr().getType().getNameAsString())
                && !queue.asObjectCreationExpr().getArguments().isEmpty();
        boolean handler = arguments.size() > WITHOUT_OPTIONS
                && HANDLER_HINTS.stream().anyMatch(arguments.get(WITHOUT_OPTIONS).toString()::contains);
        return bounded && !handler;
    }
}
