package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ru.akvine.zond.models.SourceFile;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Что в проекте используется: до каких классов и методов выполнение может дойти от точек входа.
 * <p>
 * Точки входа - то, что вызывает не код проекта: классы и методы с аннотациями фреймворков, main, тесты,
 * геттеры и сеттеры, переопределенные методы. От них использование распространяется по ссылкам и вызовам:
 * метод, который вызывается только из неиспользуемого метода, тоже не используется.
 * <p>
 * Ответ "используется" дается с запасом. Вызов, который не удалось сопоставить с конкретным методом,
 * засчитывается всем методам проекта с таким именем; имя, встретившееся в строке (рефлексия, SpEL),
 * тоже считается использованием.
 */
public final class ProjectUsages {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final String TEST_DIRECTORY = "test";
    private static final String MAIN = "main";

    // Аннотации, которые не означают, что кодом пользуется фреймворк
    private static final Set<String> NEUTRAL_ANNOTATIONS = Set.of(
            "Deprecated", "SuppressWarnings", "SafeVarargs", "FunctionalInterface", "Nullable", "NonNull", "NotNull",
            // Lombok только дописывает код
            "Data", "Getter", "Setter", "Value", "Builder", "ToString", "EqualsAndHashCode", "With",
            "AllArgsConstructor", "NoArgsConstructor", "RequiredArgsConstructor", "UtilityClass", "Accessors",
            "FieldDefaults", "Slf4j", "Log4j2", "Log", "CommonsLog");

    // getName(), setName(...), isActive(): их вызывают по имени свойства Jackson, JPA, шаблоны и Spring
    private static final Pattern ACCESSOR = Pattern.compile("^(get|set|is)[A-Z].*");

    // Методы, которые вызывает сама JVM или стандартная библиотека
    private static final Set<String> STANDARD_METHODS = Set.of(
            "equals", "hashCode", "toString", "compareTo", "clone", "finalize", "close",
            "readObject", "writeObject", "readResolve", "writeReplace", "valueOf", "values");

    // Имя -> все места в проекте, где оно записано
    private final Map<String, List<Node>> identifiers = new HashMap<>();
    private final Map<String, List<TypeDeclaration<?>>> typesByName = new HashMap<>();
    private final Map<String, List<MethodDeclaration>> methodsByName = new HashMap<>();
    private final List<TypeDeclaration<?>> types = new ArrayList<>();

    // Слова из строк: Class.forName("demo.Report"), @PreAuthorize("@access.check(#id)")
    private final Set<String> stringWords = new HashSet<>();
    private final Set<CompilationUnit> testUnits = identitySet();

    // Что вызывает каждый метод. Ключ null - код вне методов: конструкторы, инициализаторы, значения полей
    private final Map<MethodDeclaration, Calls> callsByMethod = new IdentityHashMap<>();
    private final List<SiteOutsideMethod> sitesOutsideMethods = new ArrayList<>();
    private final Map<MethodDeclaration, Set<MethodDeclaration>> callers = new IdentityHashMap<>();

    private final Set<TypeDeclaration<?>> liveTypes = identitySet();
    private final Set<MethodDeclaration> liveMethods = identitySet();

    // Имена, по которым из используемого кода вызывают неизвестно какой метод, и имена всех вызовов оттуда
    private final Set<String> livePossibleNames = new HashSet<>();
    private final Set<String> liveCalledNames = new HashSet<>();

    // Обе проверки (методы и классы) идут подряд по одному списку файлов - считаем один раз
    private static WeakReference<List<SourceFile>> cachedSources = new WeakReference<>(null);
    private static ProjectUsages cached;

    /**
     * Вызовы из одного метода
     *
     * @param targets       методы проекта, с которыми вызовы удалось сопоставить
     * @param possibleNames имена вызовов, которые сопоставить не удалось, и ссылок на методы
     * @param calledNames   имена всех вызовов методов проекта: по ним судим о методах без тела
     */
    private record Calls(Set<MethodDeclaration> targets, Set<String> possibleNames, Set<String> calledNames) {

        private Calls() {
            this(identitySet(), new HashSet<>(), new HashSet<>());
        }
    }

    /**
     * Вызовы из кода вне методов; засчитываются, если используется класс, в котором они стоят
     */
    private record SiteOutsideMethod(Node site, Calls calls) {
    }

    public static synchronized ProjectUsages of(List<SourceFile> sources) {
        if (cachedSources.get() != sources) {
            cached = new ProjectUsages(sources);
            cachedSources = new WeakReference<>(sources);
        }
        return cached;
    }

    private ProjectUsages(List<SourceFile> sources) {
        CallGraph graph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            if (isInTestDirectory(source)) {
                testUnits.add(source.unit());
            }
            for (Node node : source.unit().findAll(Node.class)) {
                index(node);
            }
        }
        for (SourceFile source : sources) {
            for (Node node : source.unit().findAll(Node.class)) {
                collectCalls(node, graph);
            }
        }
        markLiveTypes();
        markLiveMethods();
    }

    /**
     * @return true, если до класса не дойти от точек входа: на него нет ссылок либо ссылаются только
     * такие же неиспользуемые классы
     */
    public boolean isTypeDead(TypeDeclaration<?> type) {
        return !liveTypes.contains(type);
    }

    /**
     * @return неиспользуемые классы, которые ссылаются на этот класс; пусто, если ссылок нет вовсе
     */
    public Set<String> deadUsersOf(TypeDeclaration<?> type) {
        Set<String> users = new LinkedHashSet<>();
        for (Node reference : referencesTo(type)) {
            innermostType(reference).ifPresent(user -> users.add(user.getNameAsString()));
        }
        return users;
    }

    /**
     * @return true, если до метода не дойти от точек входа: его не вызывают либо вызывают только
     * из такого же неиспользуемого кода
     */
    public boolean isMethodDead(MethodDeclaration method) {
        Optional<TypeDeclaration<?>> owner = ownerOf(method);
        if (owner.isEmpty() || isEntryPoint(method, owner.get())) {
            return false;
        }
        String name = method.getNameAsString();
        if (livePossibleNames.contains(name)) {
            return false;
        }
        return method.getBody().isPresent() ? !liveMethods.contains(method) : !liveCalledNames.contains(name);
    }

    /**
     * @return методы проекта, из которых вызывается метод, в виде Класс.метод; сам метод не в счет
     */
    public Set<String> callersOf(MethodDeclaration method) {
        Set<String> names = new LinkedHashSet<>();
        for (MethodDeclaration caller : callers.getOrDefault(method, Set.of())) {
            if (caller != method) {
                names.add(ownerOf(caller).map(owner -> owner.getNameAsString() + ".").orElse("")
                        + caller.getNameAsString());
            }
        }
        return names;
    }

    private void index(Node node) {
        if (node instanceof TypeDeclaration<?> type) {
            types.add(type);
            typesByName.computeIfAbsent(type.getNameAsString(), name -> new ArrayList<>()).add(type);
        } else if (node instanceof MethodDeclaration method) {
            methodsByName.computeIfAbsent(method.getNameAsString(), name -> new ArrayList<>()).add(method);
        } else if (node instanceof SimpleName name) {
            identifiers.computeIfAbsent(name.getIdentifier(), key -> new ArrayList<>()).add(name);
        } else if (node instanceof Name name) {
            identifiers.computeIfAbsent(name.getIdentifier(), key -> new ArrayList<>()).add(name);
        } else if (node instanceof StringLiteralExpr literal) {
            collectWords(literal.getValue());
        } else if (node instanceof TextBlockLiteralExpr literal) {
            collectWords(literal.getValue());
        }
    }

    private void collectWords(String text) {
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            stringWords.add(matcher.group());
        }
    }

    private void collectCalls(Node node, CallGraph graph) {
        if (node instanceof MethodReferenceExpr reference) {
            callsAt(node).possibleNames().add(reference.getIdentifier());
            return;
        }
        if (!(node instanceof MethodCallExpr call) || !graph.hasMethodNamed(call.getNameAsString())) {
            return;
        }

        String name = call.getNameAsString();
        List<MethodDeclaration> targets = graph.targetsOf(call);
        if (!targets.isEmpty()) {
            Calls calls = callsAt(call);
            calls.calledNames().add(name);
            calls.targets().addAll(targets);
            enclosingMethod(call).ifPresent(caller -> targets.forEach(target ->
                    callers.computeIfAbsent(target, method -> identitySet()).add(caller)));
        } else if (graph.isLinked(call)) {
            // Метод найден, но у него нет ни тела, ни реализаций в проекте
            callsAt(call).calledNames().add(name);
        } else if (!Types.isLibraryCall(call)) {
            // Вызов из конструктора или инициализатора либо неразрешенный: мог попасть в любой метод с таким именем.
            // Вызов метода JDK или библиотеки к методам проекта отношения не имеет
            Calls calls = callsAt(call);
            calls.possibleNames().add(name);
            calls.calledNames().add(name);
            enclosingMethod(call).ifPresent(caller -> methodsByName.getOrDefault(name, List.of()).forEach(target ->
                    callers.computeIfAbsent(target, method -> identitySet()).add(caller)));
        }
    }

    private Calls callsAt(Node site) {
        Optional<MethodDeclaration> caller = enclosingMethod(site);
        if (caller.isPresent()) {
            return callsByMethod.computeIfAbsent(caller.get(), method -> new Calls());
        }
        Calls calls = new Calls();
        sitesOutsideMethods.add(new SiteOutsideMethod(site, calls));
        return calls;
    }

    // Используются точки входа и все, на что есть ссылка из используемого класса; так находятся и группы
    // классов, которые ссылаются только друг на друга
    private void markLiveTypes() {
        for (TypeDeclaration<?> type : types) {
            if (isEntryType(type) || stringWords.contains(type.getNameAsString())) {
                liveTypes.add(type);
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (TypeDeclaration<?> type : types) {
                if (!liveTypes.contains(type) && referencesTo(type).stream().anyMatch(this::isInLiveCode)) {
                    liveTypes.add(type);
                    changed = true;
                }
            }
        }
    }

    private void markLiveMethods() {
        Deque<Calls> queue = new ArrayDeque<>();
        for (List<MethodDeclaration> methods : methodsByName.values()) {
            for (MethodDeclaration method : methods) {
                boolean isRoot = ownerOf(method).map(owner -> isEntryPoint(method, owner)).orElse(true);
                if (isRoot && liveMethods.add(method)) {
                    queue.add(callsByMethod.getOrDefault(method, new Calls()));
                }
            }
        }
        // Конструкторы и инициализаторы выполняются, если используется их класс
        for (SiteOutsideMethod outside : sitesOutsideMethods) {
            if (isInLiveCode(outside.site())) {
                queue.add(outside.calls());
            }
        }

        while (!queue.isEmpty()) {
            Calls calls = queue.poll();
            liveCalledNames.addAll(calls.calledNames());
            livePossibleNames.addAll(calls.possibleNames());

            List<MethodDeclaration> reached = new ArrayList<>(calls.targets());
            calls.possibleNames().forEach(name -> reached.addAll(methodsByName.getOrDefault(name, List.of())));
            for (MethodDeclaration method : reached) {
                if (liveMethods.add(method)) {
                    queue.add(callsByMethod.getOrDefault(method, new Calls()));
                }
            }
        }
    }

    // Класс с аннотацией (@Service, @Entity, @Configuration) создает и вызывает фреймворк, класс с main - JVM,
    // тесты запускает сборка, аннотации используются без вызовов
    private boolean isEntryType(TypeDeclaration<?> type) {
        return type instanceof AnnotationDeclaration
                || isInTests(type)
                || isFrameworkEntry(type)
                || type.getMethods().stream().anyMatch(this::isMain);
    }

    /**
     * Метод, который вызывает не код проекта. В неиспользуемом классе точек входа нет: туда никто не попадет
     */
    private boolean isEntryPoint(MethodDeclaration method, TypeDeclaration<?> owner) {
        if (!liveTypes.contains(owner)) {
            return false;
        }
        String name = method.getNameAsString();
        return owner instanceof AnnotationDeclaration
                || isInTests(method)
                || isFrameworkEntry(method)
                || isMain(method)
                || ACCESSOR.matcher(name).matches()
                || STANDARD_METHODS.contains(name)
                || stringWords.contains(name)
                || mayOverride(method, owner, new HashSet<>());
    }

    // Любая аннотация, кроме заведомо нейтральных (@Deprecated, аннотации Lombok), - возможный вызов из фреймворка
    private boolean isFrameworkEntry(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .anyMatch(name -> !NEUTRAL_ANNOTATIONS.contains(name));
    }

    private boolean isMain(MethodDeclaration method) {
        return MAIN.equals(method.getNameAsString()) && method.isStatic();
    }

    private boolean isInTests(Node node) {
        return TestClasses.isInside(node) || node.findCompilationUnit().filter(testUnits::contains).isPresent();
    }

    private boolean isInTestDirectory(SourceFile source) {
        for (Path part : source.path()) {
            if (TEST_DIRECTORY.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    // Места, где записано имя класса, за пределами самого класса
    private List<Node> referencesTo(TypeDeclaration<?> type) {
        return identifiers.getOrDefault(type.getNameAsString(), List.of()).stream()
                .filter(usage -> usage != type.getName() && !type.isAncestorOf(usage))
                .toList();
    }

    // Код внутри используемого класса; import и package относятся ко всем классам файла сразу
    private boolean isInLiveCode(Node node) {
        Optional<TypeDeclaration<?>> type = innermostType(node);
        if (type.isPresent()) {
            return liveTypes.contains(type.get());
        }
        return node.findCompilationUnit()
                .filter(unit -> unit.getTypes().stream().anyMatch(liveTypes::contains))
                .isPresent();
    }

    private Optional<TypeDeclaration<?>> innermostType(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                return Optional.of(type);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    // Класс, в котором метод объявлен напрямую; у метода анонимного класса такого нет
    private Optional<TypeDeclaration<?>> ownerOf(MethodDeclaration method) {
        return method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?>)
                .map(parent -> (TypeDeclaration<?>) parent);
    }

    private Optional<MethodDeclaration> enclosingMethod(Node node) {
        return Nodes.enclosingCallable(node)
                .filter(callable -> callable instanceof MethodDeclaration)
                .map(callable -> (MethodDeclaration) callable);
    }

    // Метод переопределяет метод предка либо может его переопределять: у класса есть предок не из проекта,
    // и какие у того методы - неизвестно
    private boolean mayOverride(MethodDeclaration method, TypeDeclaration<?> owner, Set<String> visited) {
        if (!visited.add(owner.getNameAsString())) {
            return false;
        }
        for (ClassOrInterfaceType parent : declaredParents(owner)) {
            List<TypeDeclaration<?>> declarations = typesByName.getOrDefault(parent.getNameAsString(), List.of());
            // Предок не из проекта: что в нем объявлено, неизвестно. Переопределить его метод может
            // только public- или protected-метод
            if (declarations.isEmpty()) {
                if (method.isPublic() || method.isProtected()) {
                    return true;
                }
                continue;
            }
            for (TypeDeclaration<?> declaration : declarations) {
                boolean declaredInParent = declaration.getMethods().stream()
                        .anyMatch(candidate -> candidate.getNameAsString().equals(method.getNameAsString())
                                && candidate.getParameters().size() == method.getParameters().size());
                if (declaredInParent || mayOverride(method, declaration, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ClassOrInterfaceType> declaredParents(TypeDeclaration<?> type) {
        List<ClassOrInterfaceType> parents = new ArrayList<>();
        if (type instanceof NodeWithExtends<?> withExtends) {
            parents.addAll(withExtends.getExtendedTypes());
        }
        if (type instanceof NodeWithImplements<?> withImplements) {
            parents.addAll(withImplements.getImplementedTypes());
        }
        return parents;
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
