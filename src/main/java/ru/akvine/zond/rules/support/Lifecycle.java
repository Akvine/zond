package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.Type;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Методы, которые выполняются только при запуске или остановке приложения. Их вызывает контейнер
 * (@PostConstruct, close() у AutoCloseable-бина) либо код запуска другого бина, напрямую или через цепочку
 * ({@code MasterServer.@PostConstruct -> masterRegistryClient.start() -> registryClient.setStoppable(...)}).
 * Бин в это время запросы не обслуживает, а сам метод выполняется один раз.
 */
public final class Lifecycle {
    private static final String PRE_DESTROY = "PreDestroy";
    private static final String OVERRIDE = "Override";
    // Методы, которые контейнер вызывает сам: destroy() у DisposableBean и close() у AutoCloseable - при остановке,
    // start() и stop() у Lifecycle - при запуске и остановке контекста
    private static final Map<String, Set<String>> CALLBACKS = Map.of(
            "destroy", Set.of("DisposableBean"),
            "close", Set.of("AutoCloseable", "Closeable"),
            "start", Set.of("Lifecycle", "SmartLifecycle"),
            "stop", Set.of("Lifecycle", "SmartLifecycle"));
    // Длина цепочки вызовов от запуска: MasterServer.@PostConstruct -> workflowEngine.start() -> coordinator.start()
    private static final int MAX_CALL_DEPTH = 4;

    private static WeakReference<List<SourceFile>> cachedSources = new WeakReference<>(null);
    private static Lifecycle cached;

    private final CallGraph graph;
    // Вызовы, которые граф не сопоставил ни с одним методом проекта: тип получателя не определен, и такой вызов
    // может попасть в любой одноименный метод
    private final Map<String, List<MethodCallExpr>> unlinked = new HashMap<>();
    // Ссылки на методы по имени (this::start, worker::start): такой метод вызывается неизвестно когда
    private final Map<String, List<MethodReferenceExpr>> referenced = new HashMap<>();
    private final Map<MethodDeclaration, Boolean> known = Collections.synchronizedMap(new IdentityHashMap<>());

    public static synchronized Lifecycle of(List<SourceFile> sources) {
        if (cachedSources.get() != sources) {
            cached = new Lifecycle(sources);
            cachedSources = new WeakReference<>(sources);
        }
        return cached;
    }

    private Lifecycle(List<SourceFile> sources) {
        graph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            // Тесты в рабочей среде не выполняются: их вызовы не говорят, когда метод работает в приложении
            if (TestPaths.isTest(source.path())) {
                continue;
            }
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                if (!graph.isLinked(call)) {
                    unlinked.computeIfAbsent(call.getNameAsString(), name -> new ArrayList<>()).add(call);
                }
            }
            for (MethodReferenceExpr reference : source.unit().findAll(MethodReferenceExpr.class)) {
                referenced.computeIfAbsent(reference.getIdentifier(), name -> new ArrayList<>()).add(reference);
            }
        }
    }

    /**
     * @return true, если узел выполняется при запуске или остановке: в методе запуска своего класса либо прямо
     * в теле метода, который выполняется только тогда
     */
    public boolean isAtStartupOrShutdown(Node node) {
        return CodeContexts.isStartup(node) || isInLifecycleOnlyMethod(node, new HashSet<>());
    }

    /**
     * Метод, на который есть ссылка (this::start) либо который помечен для фреймворка (@GetMapping, @Scheduled),
     * может выполниться когда угодно. Метод без вызовов в проекте тоже: его вызывает кто-то снаружи
     */
    public boolean isLifecycleOnly(MethodDeclaration method) {
        return isLifecycleOnly(method, new HashSet<>());
    }

    /**
     * @return true для метода, который контейнер вызывает при запуске или остановке сам
     */
    public static boolean isCallback(MethodDeclaration method) {
        if (CodeContexts.isStartupMethod(method) || Annotations.has(method, PRE_DESTROY)) {
            return true;
        }
        Set<String> interfaces = CALLBACKS.get(method.getNameAsString());
        return interfaces != null && method.getParameters().isEmpty()
                && method.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(owner -> owner.getImplementedTypes().stream()
                        .anyMatch(implemented -> interfaces.contains(implemented.getNameAsString())))
                .isPresent();
    }

    private boolean isLifecycleOnly(MethodDeclaration method, Set<MethodDeclaration> visiting) {
        if (isCallback(method)) {
            return true;
        }
        Boolean result = known.get(method);
        if (result != null) {
            return result;
        }
        Optional<ClassOrInterfaceDeclaration> owner = method.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration)
                .map(parent -> (ClassOrInterfaceDeclaration) parent);
        if (owner.isEmpty() || isReferenced(method, owner.get())
                || method.getAnnotations().stream().anyMatch(annotation -> !OVERRIDE.equals(annotation.getNameAsString()))
                || visiting.size() == MAX_CALL_DEPTH || !visiting.add(method)) {
            return false;
        }
        // Обход останавливается на первом вызове во время работы: остальные одноименные вызовы проекта уже не важны
        boolean called = false;
        boolean lifecycleOnly = true;
        Iterator<MethodCallExpr> sites = callers(method, owner.get()).iterator();
        while (lifecycleOnly && sites.hasNext()) {
            MethodCallExpr call = sites.next();
            called = true;
            lifecycleOnly = CodeContexts.isStartup(call) || isInLifecycleOnlyMethod(call, visiting);
        }
        lifecycleOnly &= called;
        visiting.remove(method);
        known.put(method, lifecycleOnly);
        return lifecycleOnly;
    }

    // Вызовы, сопоставленные с методом, и несопоставленные одноименные, которые могут в него попасть
    private Stream<MethodCallExpr> callers(MethodDeclaration method, ClassOrInterfaceDeclaration owner) {
        boolean varargs = method.getParameters().stream().anyMatch(Parameter::isVarArgs);
        Stream<MethodCallExpr> linked = graph.callsTo(method).stream()
                .map(CallGraph.Call::site)
                .filter(call -> !isInTest(call));
        Stream<MethodCallExpr> guessed = unlinked.getOrDefault(method.getNameAsString(), List.of()).stream()
                .filter(call -> varargs || call.getArguments().size() == method.getParameters().size())
                .filter(call -> !isInTest(call))
                .filter(call -> mayCall(call, owner));
        // Одинаковые по тексту вызовы в разных методах - разные места: сравниваем по самому узлу, а не по содержимому
        Set<MethodCallExpr> sites = Collections.newSetFromMap(new IdentityHashMap<>());
        return Stream.concat(linked, guessed).filter(sites::add);
    }

    private boolean isInTest(Node node) {
        return node.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(storage -> TestPaths.isTest(storage.getPath()))
                .orElse(false) || TestClasses.isInside(node);
    }

    // Вызов прямо в теле такого метода, а не в лямбде или анонимном классе: те выполнятся позже, во время работы
    private boolean isInLifecycleOnlyMethod(Node node, Set<MethodDeclaration> visiting) {
        return Nodes.enclosingCallable(node)
                .filter(callable -> callable instanceof MethodDeclaration)
                .filter(callable -> !Nodes.isInNestedScope(node, callable))
                .map(callable -> isLifecycleOnly((MethodDeclaration) callable, visiting))
                .orElse(false);
    }

    // Ссылка на метод этого класса: this::start в нем самом, AlertRegistryClient::start, client::start
    private boolean isReferenced(MethodDeclaration method, ClassOrInterfaceDeclaration owner) {
        return referenced.getOrDefault(method.getNameAsString(), List.of()).stream()
                .anyMatch(reference -> mayReach(reference, Optional.of(reference.getScope()), owner));
    }

    private boolean mayCall(MethodCallExpr call, ClassOrInterfaceDeclaration type) {
        return mayReach(call, call.getScope(), type);
    }

    /**
     * Может ли вызов или ссылка попасть в метод класса: без объекта либо через this - из самого класса, вложенного
     * в него или наследника; через объект - если его тип этот класс, его предок либо не определен
     */
    private boolean mayReach(Node site, Optional<Expression> receiver, ClassOrInterfaceDeclaration type) {
        Optional<Expression> scope = receiver.filter(expression -> !expression.isThisExpr());
        boolean inSubclass = site.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(caller -> caller.getExtendedTypes().stream()
                        .anyMatch(extended -> extended.getNameAsString().equals(type.getNameAsString())))
                .isPresent();
        if (scope.isEmpty()) {
            return type.isAncestorOf(site) || inSubclass;
        }
        // super.start() идет в метод родителя: в этот класс - только из его наследника
        if (scope.get().isSuperExpr()) {
            return inSubclass;
        }
        Set<String> names = Stream.concat(Stream.of(type.getNameAsString()),
                        Stream.concat(type.getExtendedTypes().stream(), type.getImplementedTypes().stream())
                                .map(NodeWithSimpleName::getNameAsString))
                .collect(Collectors.toSet());
        // AlertRegistryClient::start - ссылка через имя типа
        if (scope.get().isTypeExpr()) {
            Type referencedType = scope.get().asTypeExpr().getType();
            return names.contains(referencedType.isClassOrInterfaceType()
                    ? referencedType.asClassOrInterfaceType().getNameAsString() : referencedType.asString());
        }
        if (scope.get().isNameExpr() && names.contains(scope.get().asNameExpr().getNameAsString())) {
            return true;
        }
        return declaredTypeOf(scope.get()).map(names::contains).orElse(true);
    }

    /**
     * Тип получателя по объявлению рядом: переменная, параметр, поле, new Foo(), Foo.start(). Решатель типов здесь
     * не годится: одноименных вызовов в проекте тысячи, а он работает под общей блокировкой и тормозит остальные
     * правила. Объявление не нашлось - тип неизвестен
     */
    private Optional<String> declaredTypeOf(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isObjectCreationExpr()) {
            return Optional.of(value.asObjectCreationExpr().getType().getNameAsString());
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(value);
        if (declaration.isEmpty()) {
            // Foo.start() - статический вызов через имя класса
            return value.isNameExpr() && Character.isUpperCase(value.asNameExpr().getNameAsString().charAt(0))
                    ? Optional.of(value.asNameExpr().getNameAsString())
                    : Optional.empty();
        }
        boolean inferred = declaration.get() instanceof VariableDeclarator variable
                && (variable.getType().isVarType() || Lombok.isVal(variable.getType()));
        return inferred ? Optional.empty() : LocalTypes.declaredType(declaration.get());
    }
}
