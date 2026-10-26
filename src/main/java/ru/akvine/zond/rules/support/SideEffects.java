package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
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
 * Есть ли у метода проекта действие помимо результата. Метод без действия только вычисляет значение: не пишет
 * в поля и чужие объекты, не бросает исключение, не вызывает ничего, что меняет состояние, - вызов такого метода
 * отдельной строкой ничего не делает.
 * <p>
 * Методы проекта разбираются по их телу, методы библиотек - по списку заведомо безопасных: чтение строк и
 * коллекций, геттеры, Math, Objects, String.valueOf. Все остальное считается действием: так анализ ошибается
 * только в сторону "у метода есть действие"
 */
public final class SideEffects {
    // Методы библиотек, которые только читают объект либо строят новый
    private static final Set<String> READING_METHODS = Set.of(
            "equals", "equalsIgnoreCase", "hashCode", "toString", "compareTo", "compareToIgnoreCase", "length",
            "size", "isEmpty", "isBlank", "contains", "containsKey", "containsValue", "containsAll", "startsWith",
            "endsWith", "indexOf", "lastIndexOf", "charAt", "substring", "subSequence", "trim", "strip", "toLowerCase",
            "toUpperCase", "split", "concat", "matches", "format", "formatted", "valueOf", "join",
            "repeat", "chars", "toCharArray", "getBytes", "intern", "get", "getOrDefault", "keySet", "values",
            "entrySet", "stream", "map", "filter", "flatMap", "mapToInt", "mapToLong", "mapToObj", "sorted",
            "distinct", "limit", "skip", "collect", "toList", "toSet", "count", "sum", "min", "max", "average",
            "anyMatch", "allMatch", "noneMatch", "findFirst", "findAny", "reduce", "boxed", "orElse", "orElseGet",
            "isPresent", "ifPresentOrElse", "of", "ofNullable", "empty", "asList", "copyOf", "abs", "sqrt", "pow",
            "round", "floor", "ceil", "signum", "isNull", "nonNull", "requireNonNullElse", "hash", "name", "ordinal",
            "getClass", "plus", "minus", "multiply", "divide", "negate", "subtract", "scale", "setScale",
            "unmodifiableList", "unmodifiableSet", "unmodifiableMap", "emptyList", "emptySet", "emptyMap",
            "singletonList", "singleton", "singletonMap", "nCopies");
    // У строки replace возвращает новую строку, у Map - меняет карту
    private static final Set<String> STRING_METHODS = Set.of("replace", "replaceAll", "replaceFirst");
    private static final String STRING = "String";
    // Чтения, которые бросают исключение на плохом значении: Integer.parseInt(text) отдельной строкой - проверка
    private static final List<String> THROWING_PREFIXES = List.of("parse", "fromString", "decode");
    private static final List<String> GETTER_PREFIXES = List.of("get", "is", "has");
    // Объекты, создание которых ничего не меняет вокруг
    private static final Set<String> VALUE_TYPES = Set.of(
            "Object", "String", "StringBuilder", "StringBuffer", "StringJoiner", "ArrayList", "LinkedList", "HashMap",
            "LinkedHashMap", "TreeMap", "HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque", "EnumMap", "BigDecimal",
            "BigInteger", "Integer", "Long", "Double", "Boolean");

    private static WeakReference<List<SourceFile>> cachedSources = new WeakReference<>(null);
    private static SideEffects cached;

    private final CallGraph graph;
    // Классы проекта по простому имени: по конструктору видно, меняет ли что-то new Order(...)
    private final Map<String, TypeDeclaration<?>> types = new HashMap<>();
    // Имя класса или интерфейса -> классы проекта, которые его расширяют или реализуют
    private final Map<String, List<TypeDeclaration<?>>> subtypes = new HashMap<>();
    private final Map<MethodDeclaration, Boolean> known = new IdentityHashMap<>();
    private final Set<MethodDeclaration> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());

    private SideEffects(List<SourceFile> sources) {
        this.graph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            for (TypeDeclaration<?> type : source.unit().findAll(TypeDeclaration.class).stream()
                    .map(found -> (TypeDeclaration<?>) found).toList()) {
                types.putIfAbsent(type.getNameAsString(), type);
                List<ClassOrInterfaceType> parents = new ArrayList<>();
                if (type instanceof NodeWithExtends<?> withExtends) {
                    parents.addAll(withExtends.getExtendedTypes());
                }
                if (type instanceof NodeWithImplements<?> withImplements) {
                    parents.addAll(withImplements.getImplementedTypes());
                }
                parents.forEach(parent -> subtypes.computeIfAbsent(parent.getNameAsString(), name -> new ArrayList<>()).add(type));
            }
        }
    }

    public static synchronized SideEffects of(List<SourceFile> sources) {
        if (cachedSources.get() != sources) {
            cached = new SideEffects(sources);
            cachedSources = new WeakReference<>(sources);
        }
        return cached;
    }

    /**
     * @return true, если метод только вычисляет результат: вызов отдельной строкой ничего не делает
     */
    public synchronized boolean isPure(MethodDeclaration method) {
        Boolean ready = known.get(method);
        if (ready != null) {
            return ready;
        }
        // Рекурсия: о методе, который еще разбирается, ничего не известно - считаем, что действие у него есть
        if (method.getBody().isEmpty() || !inProgress.add(method)) {
            return false;
        }
        boolean pure;
        try {
            // Вызов может попасть в переопределение в подклассе: оно тоже должно быть без действий
            pure = hasNoEffects(method) && overrides(method).stream().allMatch(this::isPure);
        } finally {
            inProgress.remove(method);
        }
        known.put(method, pure);
        return pure;
    }

    private List<MethodDeclaration> overrides(MethodDeclaration method) {
        if (method.isPrivate() || method.isStatic()) {
            return List.of();
        }
        List<MethodDeclaration> found = new ArrayList<>();
        method.findAncestor(TypeDeclaration.class)
                .ifPresent(type -> collectOverrides(type.getNameAsString(), method, found, new HashSet<>()));
        return found;
    }

    private void collectOverrides(String typeName, MethodDeclaration method, List<MethodDeclaration> found, Set<String> visited) {
        if (!visited.add(typeName)) {
            return;
        }
        for (TypeDeclaration<?> subtype : subtypes.getOrDefault(typeName, List.of())) {
            subtype.getMethodsByName(method.getNameAsString()).stream()
                    .filter(candidate -> candidate.getParameters().size() == method.getParameters().size())
                    .forEach(found::add);
            collectOverrides(subtype.getNameAsString(), method, found, visited);
        }
    }

    private boolean hasNoEffects(Node callable) {
        Set<String> locals = new HashSet<>();
        Set<String> fresh = new HashSet<>();
        if (callable instanceof MethodDeclaration method) {
            method.getParameters().stream().map(Parameter::getNameAsString).forEach(locals::add);
        }
        for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
            if (variable.findAncestor(TypeDeclaration.class).filter(type -> type.isAncestorOf(callable)).isEmpty()) {
                continue;
            }
            locals.add(variable.getNameAsString());
            // Объект, созданный здесь же: менять его можно, снаружи этого не видно
            variable.getInitializer().map(Nodes::unwrap)
                    .filter(value -> value.isObjectCreationExpr() || value.isArrayCreationExpr()
                            || value.isArrayInitializerExpr())
                    .ifPresent(value -> fresh.add(variable.getNameAsString()));
        }
        for (Node node : callable.findAll(Node.class)) {
            // items.forEach(this::save): что вызовет ссылка на метод, по ней не видно
            if (node instanceof ThrowStmt || node instanceof SynchronizedStmt || node instanceof MethodReferenceExpr) {
                return false;
            }
            if (node instanceof AssignExpr assignment && !isLocalTarget(assignment.getTarget(), locals, fresh)) {
                return false;
            }
            if (node instanceof UnaryExpr unary && changes(unary) && !isLocalTarget(unary.getExpression(), locals, fresh)) {
                return false;
            }
            if (node instanceof MethodCallExpr call && !isReading(call, fresh)) {
                return false;
            }
            if (node instanceof ObjectCreationExpr creation && !createsValue(creation)) {
                return false;
            }
        }
        return true;
    }

    private boolean changes(UnaryExpr unary) {
        return unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
                || unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT
                || unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
                || unary.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }

    // total = ..., i++, result[i] = ... у созданного здесь массива; не this.count = ... и не order.items[0] = ...
    private boolean isLocalTarget(Expression target, Set<String> locals, Set<String> fresh) {
        Expression value = Nodes.unwrap(target);
        if (value.isNameExpr()) {
            return locals.contains(value.asNameExpr().getNameAsString());
        }
        if (value.isArrayAccessExpr()) {
            Expression array = Nodes.unwrap(value.asArrayAccessExpr().getName());
            return array.isNameExpr() && fresh.contains(array.asNameExpr().getNameAsString());
        }
        return false;
    }

    private boolean isReading(MethodCallExpr call, Set<String> fresh) {
        List<MethodDeclaration> targets = graph.targetsOf(call);
        if (!targets.isEmpty()) {
            return targets.stream().allMatch(this::isPure);
        }
        String method = call.getNameAsString();
        if (startsWithWord(method, THROWING_PREFIXES)) {
            return false;
        }
        // builder.append(x).append(y), где builder создан здесь же
        if (receiverRoot(call).filter(fresh::contains).isPresent()) {
            return true;
        }
        boolean getter = call.getArguments().isEmpty() && startsWithWord(method, GETTER_PREFIXES);
        boolean stringMethod = STRING_METHODS.contains(method)
                && call.getScope().flatMap(LocalTypes::typeOf).filter(STRING::equals).isPresent();
        return getter || stringMethod || READING_METHODS.contains(method);
    }

    private Optional<String> receiverRoot(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope().map(Nodes::unwrap);
        while (scope.isPresent() && scope.get().isMethodCallExpr()) {
            scope = scope.get().asMethodCallExpr().getScope().map(Nodes::unwrap);
        }
        return scope.filter(Expression::isNameExpr).map(value -> value.asNameExpr().getNameAsString());
    }

    // new ArrayList<>(), new BigDecimal(text), new Order(id, name) с конструктором из одних присваиваний
    private boolean createsValue(ObjectCreationExpr creation) {
        if (creation.getAnonymousClassBody().isPresent()) {
            return false;
        }
        String name = creation.getType().getNameAsString();
        if (VALUE_TYPES.contains(name)) {
            return true;
        }
        TypeDeclaration<?> type = types.get(name);
        if (type == null) {
            return false;
        }
        if (type instanceof RecordDeclaration) {
            return true;
        }
        List<ConstructorDeclaration> constructors = type.getConstructors().stream()
                .filter(constructor -> constructor.getParameters().size() == creation.getArguments().size())
                .toList();
        return constructors.stream().allMatch(this::onlyFillsFields);
    }

    // this.id = id; this.name = name; super(...) - создание объекта ничего не меняет вокруг
    private boolean onlyFillsFields(ConstructorDeclaration constructor) {
        for (Node node : constructor.getBody().findAll(Node.class)) {
            if (node instanceof ThrowStmt || node instanceof MethodCallExpr || node instanceof ExplicitConstructorInvocationStmt call
                    && !call.getArguments().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWithWord(String method, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> method.startsWith(prefix)
                && (method.length() == prefix.length() || Character.isUpperCase(method.charAt(prefix.length()))));
    }
}
