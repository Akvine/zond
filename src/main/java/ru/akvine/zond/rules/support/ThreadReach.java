package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Могут ли метод выполнять несколько потоков одновременно. Ответ ищется по графу вызовов назад, от метода
 * к точкам входа: обработчик запроса, слушатель очереди, задача по расписанию и код, отданный в пул потоков,
 * выполняются многими потоками; {@code main}, запуск приложения, служебная операция JMX и обработчики
 * оконного интерфейса - одним.
 * <p>
 * Ответ {@link Reach#SINGLE} дается, только если до каждого начала цепочки удалось дойти и все они однопоточные.
 * Метод без вызовов в проекте, ссылка на метод и вызов из отложенной лямбды дают {@link Reach#UNKNOWN}:
 * кто и когда их выполнит, по коду не видно.
 */
public final class ThreadReach {
    public enum Reach {
        /** Метод достижим из кода, который выполняют несколько потоков */
        CONCURRENT,
        /** Все цепочки вызовов начинаются в однопоточном коде */
        SINGLE,
        /** Хотя бы одна цепочка начинается неизвестно где */
        UNKNOWN
    }

    private static final Set<String> CONCURRENT_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
            "MessageMapping", "SubscribeMapping", "QueryMapping", "MutationMapping", "SchemaMapping", "ExceptionHandler",
            "GET", "POST", "PUT", "DELETE", "PATCH", "Path",
            "Scheduled", "Schedules", "Async", "EventListener", "TransactionalEventListener",
            "KafkaListener", "KafkaHandler", "RabbitListener", "RabbitHandler", "JmsListener", "SqsListener",
            "StreamListener", "ServiceActivator", "OnMessage", "OnOpen", "OnClose",
            "Around", "Before", "After", "AfterReturning", "AfterThrowing");
    // Метод без параметров в классе-задаче: его выполняет поток, которому задачу отдали
    private static final Map<String, Set<String>> TASK_METHODS = Map.of(
            "run", Set.of("Runnable", "Thread", "TimerTask"),
            "call", Set.of("Callable"),
            "get", Set.of("Supplier"),
            "doInBackground", Set.of("SwingWorker"));
    // Методы, которые фреймворк вызывает на потоке запроса или сообщения
    private static final Set<String> REQUEST_CALLBACKS = Set.of(
            "doFilter", "doFilterInternal", "service", "doGet", "doPost", "doPut", "doDelete", "preHandle",
            "postHandle", "afterCompletion", "onMessage", "handleMessage", "handleTextMessage", "channelRead",
            "channelRead0");
    private static final Set<String> SINGLE_ANNOTATIONS = Set.of(
            "PostConstruct", "PreDestroy", "Bean", "ManagedOperation", "ManagedAttribute", "ShellMethod", "Command");
    private static final Set<String> SINGLE_TYPE_ANNOTATIONS = Set.of("Command", "ShellComponent");
    // Код оконного интерфейса выполняет один поток обработки событий
    private static final List<String> UI_PACKAGES = List.of("javax.swing", "java.awt", "javafx.", "org.eclipse.swt");
    private static final Set<String> SPAWN_METHODS = Set.of(
            "execute", "submit", "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay", "invokeAll", "invokeAny",
            "supplyAsync", "runAsync", "newThread", "startVirtualThread", "ofVirtual");
    private static final String ASYNC_SUFFIX = "Async";
    private static final Set<String> SPAWN_TYPES = Set.of("Thread", "Timer", "FutureTask");
    private static final String PARALLEL = "parallel";
    // Лямбда, отданная в такой метод, выполняется сразу и тем же потоком
    private static final Set<String> IMMEDIATE_METHODS = Set.of(
            "forEach", "forEachOrdered", "map", "filter", "flatMap", "anyMatch", "allMatch", "noneMatch", "collect",
            "reduce", "ifPresent", "ifPresentOrElse", "orElseGet", "orElseThrow", "or", "computeIfAbsent",
            "computeIfPresent", "compute", "merge", "removeIf", "replaceAll", "sort", "sorted", "peek", "mapToInt",
            "mapToLong", "mapToDouble", "mapToObj", "toMap", "groupingBy", "partitioningBy", "comparing",
            "thenComparing", "min", "max", "takeWhile", "dropWhile", "iterate", "generate", "doPrivileged",
            "executeWithoutResult", "requireNonNullElseGet", "getOrElse", "fold", "use", "with");
    private static final String MAIN = "main";
    // Столько методов хватает, чтобы дойти до точек входа; дальше ответ считается неизвестным
    private static final int MAX_METHODS = 400;

    private static WeakReference<List<SourceFile>> cachedSources = new WeakReference<>(null);
    private static ThreadReach cached;

    private final CallGraph graph;
    // Вызовы, которых нет в графе: тип получателя не определен либо вызов стоит в конструкторе или инициализаторе
    private final Map<String, List<MethodCallExpr>> loose = new HashMap<>();
    private final Map<String, List<MethodReferenceExpr>> referenced = new HashMap<>();
    private final Set<CompilationUnit> testUnits = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<MethodDeclaration, Reach> known = Collections.synchronizedMap(new IdentityHashMap<>());

    public static synchronized ThreadReach of(List<SourceFile> sources) {
        if (cachedSources.get() != sources) {
            cached = new ThreadReach(sources);
            cachedSources = new WeakReference<>(sources);
        }
        return cached;
    }

    private ThreadReach(List<SourceFile> sources) {
        graph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            // Тесты в рабочей среде не выполняются: их вызовы не говорят, какой поток выполняет метод в приложении
            if (TestPaths.isTest(source.path())) {
                testUnits.add(source.unit());
                continue;
            }
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                if (!graph.isLinked(call) && graph.hasMethodNamed(call.getNameAsString())) {
                    loose.computeIfAbsent(call.getNameAsString(), name -> new ArrayList<>()).add(call);
                }
            }
            for (MethodReferenceExpr reference : source.unit().findAll(MethodReferenceExpr.class)) {
                referenced.computeIfAbsent(reference.getIdentifier(), name -> new ArrayList<>()).add(reference);
            }
        }
    }

    /**
     * @return какие потоки выполняют код в этом месте; для кода вне метода (конструктор, инициализатор) -
     * {@link Reach#SINGLE}, если объект создается при запуске, иначе {@link Reach#UNKNOWN}
     */
    public Reach of(Node node) {
        Optional<MethodDeclaration> method = node instanceof MethodDeclaration declaration
                ? Optional.of(declaration)
                : Nodes.enclosingCallable(node).filter(MethodDeclaration.class::isInstance).map(MethodDeclaration.class::cast);
        if (method.isEmpty()) {
            return CodeContexts.isStartup(node) || isUi(node) ? Reach.SINGLE : Reach.UNKNOWN;
        }
        if (isSpawned(node)) {
            return Reach.CONCURRENT;
        }
        Reach reach = known.get(method.get());
        if (reach == null) {
            reach = compute(method.get());
            known.put(method.get(), reach);
        }
        return reach;
    }

    private Reach compute(MethodDeclaration start) {
        Set<MethodDeclaration> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<MethodDeclaration> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        boolean unknown = false;
        while (!queue.isEmpty()) {
            if (seen.size() > MAX_METHODS) {
                return Reach.UNKNOWN;
            }
            MethodDeclaration method = queue.poll();
            if (isConcurrentEntry(method)) {
                return Reach.CONCURRENT;
            }
            boolean called = false;
            List<MethodCallExpr> sites = new ArrayList<>();
            graph.callsTo(method).forEach(call -> sites.add(call.site()));
            for (MethodCallExpr site : loose.getOrDefault(method.getNameAsString(), List.of())) {
                if (site.getArguments().size() == method.getParameters().size()) {
                    sites.add(site);
                }
            }
            for (MethodCallExpr site : sites) {
                if (isInTest(site)) {
                    continue;
                }
                called = true;
                if (isSpawned(site)) {
                    return Reach.CONCURRENT;
                }
                Optional<Node> callable = Nodes.enclosingCallable(site);
                if (callable.isPresent() && callable.get() instanceof MethodDeclaration caller) {
                    if (isDeferred(site)) {
                        unknown = true;
                    } else if (seen.add(caller)) {
                        queue.add(caller);
                    }
                } else if (!CodeContexts.isStartup(site) && !isUi(site)) {
                    // Конструктор или инициализатор объекта, который создают неизвестно когда
                    unknown = true;
                }
            }
            for (MethodReferenceExpr reference : referenced.getOrDefault(method.getNameAsString(), List.of())) {
                called = true;
                if (isSpawnArgument(reference)) {
                    return Reach.CONCURRENT;
                }
                if (!isUi(reference)) {
                    unknown = true;
                }
            }
            if (!called && !isSingleEntry(method)) {
                unknown = true;
            }
        }
        return unknown ? Reach.UNKNOWN : Reach.SINGLE;
    }

    private boolean isConcurrentEntry(MethodDeclaration method) {
        if (CodeContexts.isStartupMethod(method) || isSingleByAnnotation(method)) {
            return false;
        }
        if (CONCURRENT_ANNOTATIONS.stream().anyMatch(annotation -> Annotations.has(method, annotation))) {
            return true;
        }
        String name = method.getNameAsString();
        Set<String> parents = parentNames(method);
        Set<String> taskTypes = TASK_METHODS.get(name);
        if (taskTypes != null && method.getParameters().isEmpty() && parents.stream().anyMatch(taskTypes::contains)) {
            return true;
        }
        return REQUEST_CALLBACKS.contains(name) && !parents.isEmpty();
    }

    // main, запуск и остановка приложения, служебная операция, команда консоли, обработчик оконного интерфейса
    private boolean isSingleEntry(MethodDeclaration method) {
        boolean isMain = method.isStatic() && MAIN.equals(method.getNameAsString()) && method.getParameters().size() == 1;
        return isMain || CodeContexts.isStartupMethod(method) || Lifecycle.isCallback(method)
                || isSingleByAnnotation(method) || isUi(method);
    }

    private boolean isSingleByAnnotation(MethodDeclaration method) {
        if (SINGLE_ANNOTATIONS.stream().anyMatch(annotation -> Annotations.has(method, annotation))) {
            return true;
        }
        return method.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration type
                        && SINGLE_TYPE_ANNOTATIONS.stream().anyMatch(annotation -> Annotations.has(type, annotation)))
                .isPresent();
    }

    // Имена типов, которые расширяет или реализует класс метода; у анонимного класса - тип в new
    private Set<String> parentNames(MethodDeclaration method) {
        Optional<Node> parent = method.getParentNode();
        if (parent.isPresent() && parent.get() instanceof ClassOrInterfaceDeclaration type) {
            Set<String> names = new java.util.HashSet<>();
            type.getExtendedTypes().forEach(extended -> names.add(extended.getNameAsString()));
            type.getImplementedTypes().forEach(implemented -> names.add(implemented.getNameAsString()));
            return names;
        }
        if (parent.isPresent() && parent.get() instanceof ObjectCreationExpr creation) {
            return Set.of(creation.getType().getNameAsString());
        }
        return Set.of();
    }

    /**
     * @return true, если код стоит в лямбде или анонимном классе, которые отданы другому потоку:
     * {@code executor.submit(() -> ...)}, {@code new Thread(() -> ...)}, {@code list.parallelStream().forEach(...)}
     */
    private boolean isSpawned(Node node) {
        Node current = node;
        while (current != null && !(current instanceof MethodDeclaration)) {
            if ((current instanceof LambdaExpr || current instanceof ObjectCreationExpr creation
                    && creation.getAnonymousClassBody().isPresent()) && isSpawnArgument((Expression) current)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        // Метод анонимного класса: сам класс мог быть отдан другому потоку
        if (current instanceof MethodDeclaration method && method.getParentNode().orElse(null) instanceof ObjectCreationExpr creation) {
            return isSpawnArgument(creation) || isSpawned(creation);
        }
        return false;
    }

    private boolean isSpawnArgument(Expression argument) {
        Node parent = argument.getParentNode().orElse(null);
        if (parent instanceof ObjectCreationExpr creation) {
            return SPAWN_TYPES.contains(creation.getType().getNameAsString());
        }
        if (!(parent instanceof MethodCallExpr call) || !call.getArguments().contains(argument)) {
            return false;
        }
        String name = call.getNameAsString();
        boolean isParallel = call.getScope().map(Node::toString).filter(scope -> scope.contains(PARALLEL)).isPresent();
        return SPAWN_METHODS.contains(name) || name.endsWith(ASYNC_SUFFIX) || isParallel;
    }

    /**
     * @return true, если вызов стоит в лямбде, которую выполнят неизвестно когда и неизвестно каким потоком:
     * она отдана не в пул потоков и не в метод, выполняющий ее на месте
     */
    private boolean isDeferred(MethodCallExpr site) {
        Node current = site.getParentNode().orElse(null);
        while (current != null && !(current instanceof MethodDeclaration)) {
            if (current instanceof LambdaExpr lambda) {
                Node parent = lambda.getParentNode().orElse(null);
                boolean immediate = parent instanceof MethodCallExpr call && IMMEDIATE_METHODS.contains(call.getNameAsString());
                if (!immediate && !isUi(lambda)) {
                    return true;
                }
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean isUi(Node node) {
        return node.findCompilationUnit()
                .filter(unit -> unit.getImports().stream()
                        .anyMatch(declaration -> UI_PACKAGES.stream().anyMatch(declaration.getNameAsString()::startsWith)))
                .isPresent();
    }

    private boolean isInTest(Node node) {
        return node.findCompilationUnit().filter(testUnits::contains).isPresent();
    }
}
