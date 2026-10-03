package ru.akvine.zond.rules;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ru.akvine.zond.models.SourceFile;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Граф вызовов между методами проекта: кто кого вызывает, в том числе из другого класса и файла.
 * <p>
 * Вызов сопоставляется с методом через решатель типов. Если разрешить его не удалось, метод ищется по именам:
 * в своем классе либо в классе, которым объявлена переменная-получатель. Вызов метода интерфейса
 * или абстрактного класса ведет ко всем его реализациям в проекте.
 */
final class CallGraph {
    // Узлы дерева сравниваются по содержимому, а два одинаковых по тексту метода - разные методы
    private final Map<MethodDeclaration, List<Call>> outgoing = new IdentityHashMap<>();
    private final Map<MethodDeclaration, List<Call>> incoming = new IdentityHashMap<>();

    // Файл и строка объявления -> метод: по ним находим свой узел для метода, который нашел решатель
    private final Map<String, MethodDeclaration> byLocation = new HashMap<>();
    private final Map<String, List<MethodDeclaration>> byTypeName = new HashMap<>();
    private final Map<String, List<TypeDeclaration<?>>> subtypes = new HashMap<>();
    private final Set<String> methodNames = new HashSet<>();

    // Вызовы, для которых нашелся метод проекта
    private final Set<MethodCallExpr> linkedSites = Collections.newSetFromMap(new IdentityHashMap<>());

    // Граф нужен нескольким правилам подряд на одном и том же списке файлов - строим его один раз на сканирование
    private static WeakReference<List<SourceFile>> cachedSources = new WeakReference<>(null);
    private static CallGraph cachedGraph;

    /**
     * @param site   место вызова
     * @param caller метод, в котором стоит вызов
     * @param target вызванный метод
     */
    record Call(MethodCallExpr site, MethodDeclaration caller, MethodDeclaration target) {
    }

    static synchronized CallGraph of(List<SourceFile> sources) {
        if (cachedSources.get() != sources) {
            cachedGraph = new CallGraph(sources);
            cachedSources = new WeakReference<>(sources);
        }
        return cachedGraph;
    }

    private CallGraph(List<SourceFile> sources) {
        for (SourceFile source : sources) {
            index(source);
        }
        for (SourceFile source : sources) {
            for (MethodDeclaration caller : source.unit().findAll(MethodDeclaration.class)) {
                caller.getBody().ifPresent(body -> link(caller, body));
            }
        }
    }

    /**
     * @return вызовы методов проекта, сделанные из метода
     */
    List<Call> callsFrom(MethodDeclaration method) {
        return outgoing.getOrDefault(method, List.of());
    }

    /**
     * @return места в проекте, из которых вызывается метод
     */
    List<Call> callsTo(MethodDeclaration method) {
        return incoming.getOrDefault(method, List.of());
    }

    /**
     * @return методы проекта, на которые приходится вызов; пусто для вызова метода JDK или библиотеки
     */
    List<MethodDeclaration> targetsOf(MethodCallExpr call) {
        Optional<Node> caller = Nodes.enclosingCallable(call);
        if (caller.isEmpty() || !(caller.get() instanceof MethodDeclaration method)) {
            return List.of();
        }
        return callsFrom(method).stream().filter(edge -> edge.site() == call).map(Call::target).toList();
    }

    /**
     * @return true, если вызов сопоставлен с методом проекта
     */
    boolean isLinked(MethodCallExpr site) {
        return linkedSites.contains(site);
    }

    /**
     * @return true, если в проекте объявлен метод с таким именем
     */
    boolean hasMethodNamed(String name) {
        return methodNames.contains(name);
    }

    private void index(SourceFile source) {
        for (TypeDeclaration<?> type : types(source)) {
            for (ClassOrInterfaceType parent : declaredParents(type)) {
                subtypes.computeIfAbsent(parent.getNameAsString(), name -> new ArrayList<>()).add(type);
            }
            for (MethodDeclaration method : type.getMethods()) {
                methodNames.add(method.getNameAsString());
                byTypeName.computeIfAbsent(type.getNameAsString(), name -> new ArrayList<>()).add(method);
                location(method).ifPresent(location -> byLocation.put(location, method));
            }
        }
    }

    private void link(MethodDeclaration caller, Node body) {
        for (MethodCallExpr site : body.findAll(MethodCallExpr.class)) {
            // Разрешение вызова - дорогая операция: методов JDK с такими именами в проекте нет, их и не пробуем
            if (!methodNames.contains(site.getNameAsString())) {
                continue;
            }
            List<MethodDeclaration> declared = resolve(site);
            if (!declared.isEmpty()) {
                linkedSites.add(site);
            }
            for (MethodDeclaration target : withImplementations(declared)) {
                Call call = new Call(site, caller, target);
                outgoing.computeIfAbsent(caller, method -> new ArrayList<>()).add(call);
                incoming.computeIfAbsent(target, method -> new ArrayList<>()).add(call);
            }
        }
    }

    private List<MethodDeclaration> resolve(MethodCallExpr site) {
        Optional<MethodDeclaration> resolved = Types.declaration(site)
                .flatMap(this::location)
                .map(byLocation::get);
        return resolved.map(List::of).orElseGet(() -> resolveByNames(site));
    }

    // Без решателя: load() и this.load() - метод своего класса, service.load() - метод класса, которым объявлен service
    private List<MethodDeclaration> resolveByNames(MethodCallExpr site) {
        Optional<Expression> scope = site.getScope().map(Nodes::unwrap);
        Optional<String> typeName;
        if (scope.isEmpty() || scope.get().isThisExpr()) {
            typeName = enclosingType(site).map(type -> type.getNameAsString());
        } else {
            typeName = LocalTypes.typeOf(scope.get());
        }

        List<MethodDeclaration> candidates = typeName.map(name -> byTypeName.getOrDefault(name, List.of()))
                .orElse(List.of())
                .stream()
                .filter(method -> matches(method, site))
                .toList();
        // Несколько подходящих методов - перегрузки или одноименные классы; угадывать не будем
        return candidates.size() == 1 ? candidates : List.of();
    }

    // Вызов метода без тела (интерфейс, абстрактный класс) на деле попадает в одну из реализаций
    private List<MethodDeclaration> withImplementations(List<MethodDeclaration> targets) {
        List<MethodDeclaration> result = new ArrayList<>();
        for (MethodDeclaration target : targets) {
            if (target.getBody().isPresent()) {
                result.add(target);
                continue;
            }
            enclosingType(target).ifPresent(type -> collectImplementations(type, target, result, new HashSet<>()));
        }
        return result;
    }

    private void collectImplementations(
            TypeDeclaration<?> type, MethodDeclaration declared, List<MethodDeclaration> result, Set<String> visited) {
        if (!visited.add(type.getNameAsString())) {
            return;
        }
        for (TypeDeclaration<?> subtype : subtypes.getOrDefault(type.getNameAsString(), List.of())) {
            subtype.getMethods().stream()
                    .filter(method -> method.getBody().isPresent())
                    .filter(method -> method.getNameAsString().equals(declared.getNameAsString())
                            && method.getParameters().size() == declared.getParameters().size())
                    .forEach(result::add);
            collectImplementations(subtype, declared, result, visited);
        }
    }

    private boolean matches(MethodDeclaration method, MethodCallExpr site) {
        return method.getNameAsString().equals(site.getNameAsString())
                && method.getParameters().size() == site.getArguments().size();
    }

    private Optional<String> location(MethodDeclaration method) {
        Optional<String> file = method.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(storage -> storage.getPath().toAbsolutePath().normalize().toString());
        // Файл без пути - дерево, разобранное из строки (в тестах): метод отличаем по самому дереву
        String owner = file.orElseGet(() -> method.findCompilationUnit()
                .map(unit -> "unit@" + System.identityHashCode(unit))
                .orElse(""));
        return method.getBegin().map(position -> owner + ":" + position.line);
    }

    private Optional<TypeDeclaration<?>> enclosingType(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                return Optional.of(type);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    private List<TypeDeclaration<?>> types(SourceFile source) {
        List<TypeDeclaration<?>> types = new ArrayList<>();
        for (Node node : source.unit().findAll(Node.class)) {
            if (node instanceof TypeDeclaration<?> type) {
                types.add(type);
            }
        }
        return types;
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
}
