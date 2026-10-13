package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Reachability;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code UUID.randomUUID()} в цикле внутри задач, которые выполняются параллельно.
 * <p>
 * Все вызовы {@code randomUUID()} берут байты у одного статического {@code SecureRandom}, а он выдает их
 * под блокировкой. Один поток этого не замечает; несколько потоков, каждый из которых генерирует
 * идентификаторы в цикле, выстраиваются в очередь к этой блокировке, и параллельная генерация идет
 * не быстрее последовательной.
 */
@Component
public class RandomUuidInParallelTaskRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 6, "На сколько вызовов вглубь от параллельной задачи искать цикл с UUID.randomUUID()");

    private static final String UUID = "UUID";
    private static final String RANDOM_UUID = "randomUUID";
    private static final String ASYNC = "Async";
    // Вызовы, которые отдают переданный код другому потоку
    private static final Set<String> ASYNC_STAGES = Set.of(
            "supplyAsync", "runAsync", "thenApplyAsync", "thenAcceptAsync", "thenRunAsync", "thenComposeAsync",
            "handleAsync", "whenCompleteAsync");
    private static final Set<String> EXECUTOR_METHODS = Set.of("submit", "execute", "invokeAll", "invokeAny");
    private static final Pattern EXECUTOR =
            Pattern.compile("(?i)(executor|threadpool|forkjoinpool|\\bpool\\b|scheduler|completionservice)");
    private static final Set<String> PARALLEL_STREAMS = Set.of("parallelStream", "parallel");

    private record Site(SourceFile file, MethodCallExpr call) {
    }

    /**
     * Код, который выполняется параллельно.
     *
     * @param tasks   лямбды, отданные другому потоку
     * @param methods методы, до которых от этих лямбд и от {@code @Async} доходят вызовы
     */
    private record Parallel(Set<Node> tasks, Set<MethodDeclaration> methods) {
    }

    @Override
    public String code() {
        return RuleCodes.RANDOM_UUID_IN_PARALLEL_TASK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет UUID.randomUUID() в цикле внутри задач, которые выполняются параллельно";
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Site> sites = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (isRandomUuidInLoop(call)) {
                    sites.add(new Site(sourceFile, call));
                }
            }
        }
        // Граф вызовов строится, только если в проекте вообще есть randomUUID() в цикле
        if (sites.isEmpty()) {
            return List.of();
        }
        Parallel parallel = parallel(sourceFiles);
        return sites.stream()
                .filter(site -> isParallel(site.call(), parallel))
                .map(site -> violation(site.file(), site.call().getName(),
                        "'UUID.randomUUID()' вызывается в цикле внутри задач, которые выполняются параллельно:"
                                + " все потоки берут байты у одного общего SecureRandom под блокировкой и ждут"
                                + " друг друга; если идентификаторы не секретны, дайте задаче свой быстрый"
                                + " генератор и собирайте UUID из его чисел: new UUID(старшие, младшие) с"
                                + " выставленными версией и вариантом"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private boolean isRandomUuidInLoop(MethodCallExpr call) {
        return RANDOM_UUID.equals(call.getNameAsString())
                && call.getArguments().isEmpty()
                && call.getScope().filter(scope -> UUID.equals(scope.toString())).isPresent()
                && !TestClasses.isInside(call)
                && CodeContexts.isRepeatedOverData(call);
    }

    // Вызов стоит прямо в коде задачи либо в методе, который задача вызывает
    private boolean isParallel(MethodCallExpr call, Parallel parallel) {
        Optional<LambdaExpr> lambda = call.findAncestor(LambdaExpr.class);
        while (lambda.isPresent()) {
            if (parallel.tasks().contains(lambda.get())) {
                return true;
            }
            lambda = lambda.get().findAncestor(LambdaExpr.class);
        }
        return call.findAncestor(MethodDeclaration.class).filter(parallel.methods()::contains).isPresent();
    }

    private Parallel parallel(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        Map<MethodDeclaration, Set<Integer>> forwarders = forwarders(sourceFiles);
        // Узлы сравниваются по тексту: две одинаковые лямбды равны, хотя исполнителю отдана одна из них
        Set<Node> tasks = identitySet();
        Set<MethodDeclaration> entries = identitySet();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (Annotations.has(method, ASYNC)) {
                    entries.add(method);
                }
            }
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                for (Expression code : submitted(call, graph, forwarders)) {
                    collect(code, graph, tasks, entries);
                }
            }
            // items.parallelStream().map(item -> ...)
            for (LambdaExpr lambda : sourceFile.unit().findAll(LambdaExpr.class)) {
                if (isInParallelStream(lambda)) {
                    collect(lambda, graph, tasks, entries);
                }
            }
        }
        Set<MethodDeclaration> reached = identitySet();
        reached.addAll(entries);
        for (MethodDeclaration entry : entries) {
            reached.addAll(Reachability.within(graph, entry, value(MAX_CALL_DEPTH)));
        }
        return new Parallel(tasks, reached);
    }

    // Задача - лямбда, ссылка на метод либо переменная, в которой лежит лямбда
    private void collect(Expression code, CallGraph graph, Set<Node> tasks, Set<MethodDeclaration> entries) {
        Expression task = Nodes.unwrap(code);
        if (task.isNameExpr()) {
            task = LocalTypes.findInitializer(task).map(Nodes::unwrap).orElse(task);
        }
        if (task.isLambdaExpr()) {
            tasks.add(task);
            task.findAll(MethodCallExpr.class).forEach(call -> entries.addAll(graph.targetsOf(call)));
        } else if (task.isMethodReferenceExpr()) {
            entries.addAll(referenced(task.asMethodReferenceExpr()));
        }
    }

    /**
     * @return аргументы вызова, которые выполнит другой поток: все аргументы {@code supplyAsync} и
     * {@code executor.submit}, а у своего метода-обертки - те, что он сам передает исполнителю
     */
    private List<Expression> submitted(MethodCallExpr call, CallGraph graph,
                                       Map<MethodDeclaration, Set<Integer>> forwarders) {
        if (isSubmit(call)) {
            return call.getArguments();
        }
        List<Expression> codes = new ArrayList<>();
        for (MethodDeclaration target : graph.targetsOf(call)) {
            for (int index : forwarders.getOrDefault(target, Set.of())) {
                if (index < call.getArguments().size()) {
                    codes.add(call.getArgument(index));
                }
            }
        }
        return codes;
    }

    /**
     * Методы-обертки: принимают задачу параметром и отдают ее исполнителю.
     * <pre>
     * private CompletableFuture&lt;T&gt; submit(Supplier&lt;T&gt; task) { return CompletableFuture.supplyAsync(task, executor); }
     * </pre>
     *
     * @return метод и номера параметров, в которых приходит задача
     */
    private Map<MethodDeclaration, Set<Integer>> forwarders(List<SourceFile> sourceFiles) {
        Map<MethodDeclaration, Set<Integer>> forwarders = new IdentityHashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                Set<Integer> positions = forwarded(method);
                if (!positions.isEmpty()) {
                    forwarders.put(method, positions);
                }
            }
        }
        return forwarders;
    }

    // Номера параметров метода, которые он передает исполнителю как есть
    private Set<Integer> forwarded(MethodDeclaration method) {
        Set<String> submitted = method.findAll(MethodCallExpr.class).stream()
                .filter(this::isSubmit)
                .flatMap(call -> call.getArguments().stream())
                .map(Nodes::unwrap)
                .filter(Expression::isNameExpr)
                .map(argument -> argument.asNameExpr().getNameAsString())
                .collect(Collectors.toSet());
        Set<Integer> positions = new HashSet<>();
        for (int index = 0; index < method.getParameters().size(); index++) {
            if (submitted.contains(method.getParameter(index).getNameAsString())) {
                positions.add(index);
            }
        }
        return positions;
    }

    private <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    // execute и submit есть не только у пулов: transactionTemplate.execute(...) выполняется в том же потоке
    private boolean isSubmit(MethodCallExpr call) {
        String name = call.getNameAsString();
        return ASYNC_STAGES.contains(name)
                || EXECUTOR_METHODS.contains(name) && call.getScope().filter(this::isExecutor).isPresent();
    }

    private boolean isExecutor(Expression scope) {
        return EXECUTOR.matcher(scope.toString()).find()
                || LocalTypes.typeOf(scope).filter(type -> EXECUTOR.matcher(type).find()).isPresent();
    }

    private boolean isInParallelStream(LambdaExpr lambda) {
        return lambda.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr)
                .flatMap(parent -> ((MethodCallExpr) parent).getScope())
                .filter(chain -> chain.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> PARALLEL_STREAMS.contains(call.getNameAsString())))
                .isPresent();
    }

    // Ссылка на метод своего класса: по имени, без разбора перегрузок
    private List<MethodDeclaration> referenced(MethodReferenceExpr reference) {
        if (!reference.getScope().isThisExpr()) {
            return List.of();
        }
        return reference.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(owner -> owner.getMethodsByName(reference.getIdentifier()))
                .orElse(List.of());
    }
}
