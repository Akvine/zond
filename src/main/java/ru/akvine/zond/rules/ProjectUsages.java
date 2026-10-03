package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Что в проекте используется: какие методы вызываются и на какие классы есть ссылки.
 * <p>
 * Ответ "используется" дается с запасом. Вызов, который не удалось сопоставить с конкретным методом,
 * засчитывается всем методам проекта с таким именем; имя, встретившееся в строке (рефлексия, SpEL),
 * тоже считается использованием. Поэтому "не используется" здесь значит: в проверенном коде нет
 * ни одного места, которое могло бы вести к этому методу или классу.
 */
final class ProjectUsages {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    // Аннотации, которые не означают, что кодом пользуется фреймворк
    private static final Set<String> NEUTRAL_ANNOTATIONS = Set.of(
            "Deprecated", "SuppressWarnings", "SafeVarargs", "FunctionalInterface", "Nullable", "NonNull", "NotNull",
            // Lombok только дописывает код
            "Data", "Getter", "Setter", "Value", "Builder", "ToString", "EqualsAndHashCode", "With",
            "AllArgsConstructor", "NoArgsConstructor", "RequiredArgsConstructor", "UtilityClass", "Accessors",
            "FieldDefaults", "Slf4j", "Log4j2", "Log", "CommonsLog");

    // Методы, вызванные из другого метода проекта
    private final Set<MethodDeclaration> calledMethods = Collections.newSetFromMap(new IdentityHashMap<>());

    // Имена всех вызванных методов проекта: по ним судим о методах без тела (интерфейс, абстрактный класс)
    private final Set<String> calledNames = new HashSet<>();

    // Имена из вызовов, которые не удалось сопоставить с методом, из ссылок на методы и из строк
    private final Set<String> possiblyUsedNames = new HashSet<>();

    // Имя -> все места в проекте, где оно записано
    private final Map<String, List<Node>> identifiers = new HashMap<>();
    private final Map<String, List<TypeDeclaration<?>>> typesByName = new HashMap<>();

    // Обе проверки (методы и классы) идут подряд по одному списку файлов - считаем один раз
    private static WeakReference<List<SourceFile>> cachedSources = new WeakReference<>(null);
    private static ProjectUsages cached;

    static synchronized ProjectUsages of(List<SourceFile> sources) {
        if (cachedSources.get() != sources) {
            cached = new ProjectUsages(sources);
            cachedSources = new WeakReference<>(sources);
        }
        return cached;
    }

    private ProjectUsages(List<SourceFile> sources) {
        CallGraph graph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            for (Node node : source.unit().findAll(Node.class)) {
                collect(node, graph);
            }
        }
    }

    /**
     * @return true, если метод вызывается либо может вызываться из проверенного кода
     */
    boolean isMethodUsed(MethodDeclaration method) {
        String name = method.getNameAsString();
        return calledMethods.contains(method)
                || possiblyUsedNames.contains(name)
                || method.getBody().isEmpty() && calledNames.contains(name);
    }

    /**
     * @return true, если имя класса встречается где-либо за пределами самого класса
     */
    boolean isTypeUsed(TypeDeclaration<?> type) {
        String name = type.getNameAsString();
        if (possiblyUsedNames.contains(name)) {
            return true;
        }
        return identifiers.getOrDefault(name, List.of()).stream()
                .anyMatch(usage -> usage != type.getName() && !type.isAncestorOf(usage));
    }

    /**
     * @return true, если метод переопределяет метод предка либо может его переопределять:
     * у класса есть предок не из проекта, и какие у того методы - неизвестно
     */
    boolean mayOverride(MethodDeclaration method, TypeDeclaration<?> owner) {
        return mayOverride(method, owner, new HashSet<>());
    }

    /**
     * @return true, если на узле есть аннотация, по которой код может вызывать фреймворк:
     * любая, кроме заведомо нейтральных (@Deprecated, аннотации Lombok)
     */
    boolean isFrameworkEntry(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .anyMatch(name -> !NEUTRAL_ANNOTATIONS.contains(name));
    }

    private void collect(Node node, CallGraph graph) {
        if (node instanceof TypeDeclaration<?> type) {
            typesByName.computeIfAbsent(type.getNameAsString(), name -> new ArrayList<>()).add(type);
        } else if (node instanceof SimpleName name) {
            identifiers.computeIfAbsent(name.getIdentifier(), key -> new ArrayList<>()).add(name);
        } else if (node instanceof Name name) {
            identifiers.computeIfAbsent(name.getIdentifier(), key -> new ArrayList<>()).add(name);
        } else if (node instanceof MethodCallExpr call) {
            collectCall(call, graph);
        } else if (node instanceof MethodReferenceExpr reference) {
            possiblyUsedNames.add(reference.getIdentifier());
        } else if (node instanceof StringLiteralExpr literal) {
            collectWords(literal.getValue());
        } else if (node instanceof TextBlockLiteralExpr literal) {
            collectWords(literal.getValue());
        }
    }

    private void collectCall(MethodCallExpr call, CallGraph graph) {
        String name = call.getNameAsString();
        if (!graph.hasMethodNamed(name)) {
            return;
        }

        List<MethodDeclaration> targets = graph.targetsOf(call);
        if (!targets.isEmpty()) {
            calledNames.add(name);
            MethodDeclaration caller = Nodes.enclosingCallable(call)
                    .filter(callable -> callable instanceof MethodDeclaration)
                    .map(callable -> (MethodDeclaration) callable)
                    .orElse(null);
            // Вызов метода из самого себя использованием не считается
            targets.stream().filter(target -> target != caller).forEach(calledMethods::add);
            return;
        }
        if (graph.isLinked(call)) {
            // Метод найден, но у него нет ни тела, ни реализаций в проекте
            calledNames.add(name);
            return;
        }
        // Вызов из конструктора или инициализатора либо неразрешенный: мог попасть в любой метод с таким именем.
        // Вызов метода JDK или библиотеки к методам проекта отношения не имеет
        if (!Types.isLibraryCall(call)) {
            possiblyUsedNames.add(name);
            calledNames.add(name);
        }
    }

    // Class.forName("demo.Report"), @PreAuthorize("@access.check(#id)"): имя в строке - возможное обращение
    private void collectWords(String text) {
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            possiblyUsedNames.add(matcher.group());
        }
    }

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
}
