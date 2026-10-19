package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Рекурсивный обход документа XML, JSON или YAML: каждый уровень вложенности - еще один вызов в стеке потока.
 * Глубину задает сам файл, и на глубоко вложенном документе (в том числе собранном специально) поток падает
 * со StackOverflowError. Это Error: catch (Exception) его не ловит, и запрос обрывается целиком. Обход циклом
 * с явным стеком (ArrayDeque) держит необработанные элементы в куче, и глубину ограничивает только память.
 */
@Component
public class RecursiveDocumentTraversalRule extends AbstractRule {
    private static final String JSON = "JSON";
    private static final String XML = "XML";
    private static final String YAML = "YAML";

    // Узлы дерева, имя которых однозначно называет формат
    private static final Map<String, String> TREE_TYPES = Map.ofEntries(
            // Jackson
            Map.entry("JsonNode", JSON), Map.entry("ObjectNode", JSON), Map.entry("ArrayNode", JSON),
            Map.entry("ContainerNode", JSON),
            // Gson, Vert.x, javax.json / jakarta.json
            Map.entry("JsonElement", JSON), Map.entry("JsonObject", JSON), Map.entry("JsonArray", JSON),
            Map.entry("JsonValue", JSON), Map.entry("JsonStructure", JSON),
            // org.json, fastjson
            Map.entry("JSONObject", JSON), Map.entry("JSONArray", JSON),
            // DOM
            Map.entry("NodeList", XML), Map.entry("NamedNodeMap", XML));

    // Потоковые читатели: рекурсивный разбор передает в себя тот же читатель и спускается на уровень ниже
    private static final Map<String, String> READER_TYPES = Map.of(
            "JsonParser", JSON, "JsonReader", JSON, "XMLStreamReader", XML, "XMLEventReader", XML);

    // Node, Element, Document есть во многих библиотеках (дерево разбора Java, графы): узлом документа
    // они считаются, только если импортированы из пакета, где объявлено дерево XML или YAML
    private static final Set<String> AMBIGUOUS_TYPES = Set.of("Node", "Element", "Document");
    private static final Map<String, String> DOCUMENT_PACKAGES = Map.of(
            "org.w3c.dom", XML, "org.dom4j", XML, "org.jdom", XML, "org.jdom2", XML, "nu.xom", XML,
            "org.yaml.snakeyaml.nodes", YAML);

    // walk(node, depth + 1) с проверкой depth > MAX_DEPTH: глубина ограничена самим методом
    private static final Pattern DEPTH_NAME = Pattern.compile("(?i).*(depth|level|nesting).*");
    private static final Set<String> INTEGRAL_TYPES = Set.of("int", "long", "short", "Integer", "Long", "Short");
    private static final Set<BinaryExpr.Operator> COMPARISONS = Set.of(
            BinaryExpr.Operator.LESS, BinaryExpr.Operator.LESS_EQUALS,
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS);

    // Взаимная рекурсия ищется на столько вызовов: parseValue -> parseObject -> parseArray -> parseValue
    private static final int MAX_CYCLE = 4;
    // Столько шагов от параметра до узла в рекурсивном вызове: переменная, цикл, лямбда, цепочка вызовов
    private static final int MAX_STEPS = 8;
    private static final Set<String> REFLECTION_TYPES = Set.of("Class", "Field", "PropertyDescriptor");
    private static final String ARROW = " -> ";
    private static final char PACKAGE_SEPARATOR = '.';

    /**
     * Параметр метода, через который идет документ
     *
     * @param index  номер параметра
     * @param name   его имя
     * @param format JSON, XML или YAML
     * @param reader потоковый читатель: в рекурсивный вызов передается он сам
     */
    private record DocumentParameter(int index, String name, String format, boolean reader) {
    }

    @Override
    public String code() {
        return RuleCodes.RECURSIVE_DOCUMENT_TRAVERSAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет рекурсивный обход и разбор документов XML, JSON и YAML:"
                + " на глубоко вложенном файле он падает со StackOverflowError";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        CompilationUnit unit = sourceFile.unit();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            if (TestClasses.isInside(type)) {
                continue;
            }
            List<MethodDeclaration> methods = type.getMethods().stream()
                    .filter(method -> method.getBody().isPresent())
                    .toList();
            Map<MethodDeclaration, Set<MethodDeclaration>> calls = ownCalls(methods);
            Map<MethodDeclaration, DocumentParameter> documents = new IdentityHashMap<>();
            methods.forEach(method -> documentParameter(method, unit).ifPresent(found -> documents.put(method, found)));

            for (Map.Entry<MethodDeclaration, DocumentParameter> entry : documents.entrySet()) {
                MethodDeclaration method = entry.getKey();
                DocumentParameter document = entry.getValue();
                List<MethodDeclaration> cycle = cycleOf(method, calls);
                if (cycle.isEmpty()) {
                    continue;
                }
                boolean selfCall = calls.get(method).contains(method);
                // walk(node) -> walk(node, path): тот же узел - это не спуск по дереву
                if (selfCall && !document.reader() && !descends(method, document)) {
                    continue;
                }
                // Метод сам сравнивает глубину с пределом либо идет по полям класса; об одном цикле - одна находка,
                // на его первом методе
                if (cycle.stream().anyMatch(other -> limitsDepth(other) || walksReflection(other))
                        || !isFirst(method, cycle, documents)) {
                    continue;
                }
                String how = selfCall
                        ? "вызывает сам себя для каждого вложенного элемента"
                        : "вложенные элементы разбирает цепочка " + path(method, calls);
                violations.add(violation(sourceFile, method.getName(),
                        "Метод '" + method.getNameAsString() + "' обходит документ " + document.format()
                                + " рекурсивно (" + how + "): глубина стека равна глубине вложенности файла,"
                                + " и на глубоко вложенном документе поток упадет со StackOverflowError (catch"
                                + " (Exception) его не поймает); обходите дерево циклом - кладите элементы"
                                + " в ArrayDeque и разбирайте их, пока очередь не опустеет"));
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

    // Вызовы своих методов класса: без объекта либо через this, по имени и числу аргументов (в том числе в лямбдах)
    private Map<MethodDeclaration, Set<MethodDeclaration>> ownCalls(List<MethodDeclaration> methods) {
        Map<String, List<MethodDeclaration>> byName = methods.stream()
                .collect(Collectors.groupingBy(MethodDeclaration::getNameAsString));
        Map<MethodDeclaration, Set<MethodDeclaration>> calls = new IdentityHashMap<>();
        for (MethodDeclaration method : methods) {
            Set<MethodDeclaration> callees = Collections.newSetFromMap(new IdentityHashMap<>());
            for (MethodCallExpr call : method.getBody().orElseThrow().findAll(MethodCallExpr.class)) {
                if (call.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
                    continue;
                }
                byName.getOrDefault(call.getNameAsString(), List.of()).stream()
                        .filter(candidate -> accepts(candidate, call))
                        .forEach(callees::add);
            }
            // node.forEach(this::walk), array.forEach(JsonUtil::walk): число аргументов по ссылке не видно
            for (MethodReferenceExpr reference : method.getBody().orElseThrow().findAll(MethodReferenceExpr.class)) {
                if (reference.getScope().isThisExpr() || reference.getScope().isTypeExpr()) {
                    callees.addAll(byName.getOrDefault(reference.getIdentifier(), List.of()));
                }
            }
            calls.put(method, callees);
        }
        return calls;
    }

    private boolean accepts(MethodDeclaration method, MethodCallExpr call) {
        int parameters = method.getParameters().size();
        boolean varArgs = parameters > 0 && method.getParameter(parameters - 1).isVarArgs();
        return parameters == call.getArguments().size() || varArgs && call.getArguments().size() >= parameters - 1;
    }

    /**
     * @return методы цикла, в котором метод снова вызывает сам себя (с ним самим); пусто, если цикла нет
     */
    private List<MethodDeclaration> cycleOf(MethodDeclaration method, Map<MethodDeclaration, Set<MethodDeclaration>> calls) {
        Set<MethodDeclaration> reachable = reachableFrom(method, calls);
        if (!reachable.contains(method)) {
            return List.of();
        }
        return reachable.stream()
                .filter(other -> other == method || reachableFrom(other, calls).contains(method))
                .sorted(Comparator.comparingInt(this::line))
                .toList();
    }

    private Set<MethodDeclaration> reachableFrom(MethodDeclaration start, Map<MethodDeclaration, Set<MethodDeclaration>> calls) {
        Set<MethodDeclaration> reached = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<MethodDeclaration> current = new ArrayDeque<>(calls.getOrDefault(start, Set.of()));
        for (int step = 0; step < MAX_CYCLE && !current.isEmpty(); step++) {
            Deque<MethodDeclaration> next = new ArrayDeque<>();
            for (MethodDeclaration method : current) {
                if (reached.add(method)) {
                    next.addAll(calls.getOrDefault(method, Set.of()));
                }
            }
            current = next;
        }
        return reached;
    }

    /**
     * walk(children.item(i)), flatten(prefix, entry.getValue()), node.forEach(this::walk): в вызов уходит узел,
     * до которого дошли от параметра. Тот же узел (walk(node, path)), новый объект (validate(new DOMSource(node)))
     * и результат другой функции (merge(parent, node)) - не спуск по документу
     */
    private boolean descends(MethodDeclaration method, DocumentParameter document) {
        BlockStmt body = method.getBody().orElseThrow();
        boolean byCall = body.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals(method.getNameAsString()) && accepts(method, call))
                .filter(call -> call.getScope().filter(scope -> !scope.isThisExpr()).isEmpty())
                .filter(call -> call.getArguments().size() > document.index())
                .map(call -> Nodes.unwrap(call.getArgument(document.index())))
                .anyMatch(argument -> !isParameter(argument, document) && isReachedFrom(argument, document, method, 0));
        return byCall || body.findAll(MethodReferenceExpr.class).stream()
                .filter(reference -> reference.getIdentifier().equals(method.getNameAsString()))
                .map(reference -> reference.getParentNode().orElse(null))
                .filter(owner -> owner instanceof MethodCallExpr)
                .flatMap(owner -> ((MethodCallExpr) owner).getScope().stream())
                .anyMatch(scope -> isReachedFrom(scope, document, method, 0));
    }

    // Выражение - сам параметр либо узел, до которого от него дошли: по цепочке вызовов (node.get(i).path(x)),
    // через переменную, переменную цикла по нему или параметр лямбды в node.forEach(child -> ...)
    private boolean isReachedFrom(Expression expression, DocumentParameter document, MethodDeclaration method, int steps) {
        Expression value = Nodes.unwrap(expression);
        if (steps > MAX_STEPS) {
            return false;
        }
        if (value.isCastExpr()) {
            return isReachedFrom(value.asCastExpr().getExpression(), document, method, steps + 1);
        }
        if (value.isConditionalExpr()) {
            return isReachedFrom(value.asConditionalExpr().getThenExpr(), document, method, steps + 1)
                    || isReachedFrom(value.asConditionalExpr().getElseExpr(), document, method, steps + 1);
        }
        if (value.isArrayAccessExpr()) {
            return isReachedFrom(value.asArrayAccessExpr().getName(), document, method, steps + 1);
        }
        if (value.isMethodCallExpr()) {
            return value.asMethodCallExpr().getScope()
                    .filter(scope -> isReachedFrom(scope, document, method, steps + 1))
                    .isPresent();
        }
        if (!(value instanceof NameExpr name)) {
            return false;
        }
        if (isParameter(name, document)) {
            return true;
        }
        String variable = name.getNameAsString();
        boolean byVariable = method.findAll(VariableDeclarator.class).stream()
                .filter(declarator -> declarator.getNameAsString().equals(variable))
                .anyMatch(declarator -> declarator.getParentNode()
                        .flatMap(Node::getParentNode)
                        .filter(owner -> owner instanceof ForEachStmt)
                        .map(owner -> isReachedFrom(((ForEachStmt) owner).getIterable(), document, method, steps + 1))
                        .orElseGet(() -> declarator.getInitializer()
                                .filter(initializer -> isReachedFrom(initializer, document, method, steps + 1))
                                .isPresent()));
        // if (value instanceof JSONObject nested): nested - тот же value
        boolean byPattern = method.findAll(TypePatternExpr.class).stream()
                .filter(pattern -> pattern.getNameAsString().equals(variable))
                .map(pattern -> pattern.findAncestor(InstanceOfExpr.class))
                .flatMap(Optional::stream)
                .anyMatch(check -> isReachedFrom(check.getExpression(), document, method, steps + 1));
        return byVariable || byPattern || method.findAll(LambdaExpr.class).stream()
                .filter(lambda -> lambda.getParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(variable)))
                .map(lambda -> lambda.getParentNode().orElse(null))
                .filter(owner -> owner instanceof MethodCallExpr)
                .flatMap(owner -> ((MethodCallExpr) owner).getScope().stream())
                .anyMatch(scope -> isReachedFrom(scope, document, method, steps + 1));
    }

    private boolean isParameter(Expression expression, DocumentParameter document) {
        return expression instanceof NameExpr name && name.getNameAsString().equals(document.name());
    }

    // walkInto(Class<?> type, JsonNode json): спуск идет по полям класса, и глубину задает код, а не файл
    private boolean walksReflection(MethodDeclaration method) {
        return method.getParameters().stream()
                .anyMatch(parameter -> REFLECTION_TYPES.contains(LocalTypes.typeName(parameter.getType())));
    }

    // walk(JsonNode node, int depth) с if (depth > MAX_DEPTH): глубину ограничивает сам метод
    private boolean limitsDepth(MethodDeclaration method) {
        Set<String> depthParameters = method.getParameters().stream()
                .filter(parameter -> INTEGRAL_TYPES.contains(LocalTypes.typeName(parameter.getType())))
                .map(Parameter::getNameAsString)
                .filter(name -> DEPTH_NAME.matcher(name).matches())
                .collect(Collectors.toSet());
        return !depthParameters.isEmpty() && method.findAll(BinaryExpr.class).stream()
                .filter(comparison -> COMPARISONS.contains(comparison.getOperator()))
                .anyMatch(comparison -> isDepth(comparison.getLeft(), depthParameters)
                        || isDepth(comparison.getRight(), depthParameters));
    }

    private boolean isDepth(Expression side, Set<String> depthParameters) {
        Expression value = Nodes.unwrap(side);
        return value instanceof NameExpr name && depthParameters.contains(name.getNameAsString());
    }

    private boolean isFirst(MethodDeclaration method, List<MethodDeclaration> cycle,
                            Map<MethodDeclaration, DocumentParameter> documents) {
        return cycle.stream().filter(documents::containsKey).findFirst().filter(first -> first == method).isPresent();
    }

    // parseValue -> parseObject -> parseValue: кратчайший путь по вызовам от метода обратно к нему
    private String path(MethodDeclaration method, Map<MethodDeclaration, Set<MethodDeclaration>> calls) {
        Map<MethodDeclaration, MethodDeclaration> previous = new IdentityHashMap<>();
        Deque<MethodDeclaration> queue = new ArrayDeque<>(List.of(method));
        while (!queue.isEmpty()) {
            MethodDeclaration current = queue.removeFirst();
            List<MethodDeclaration> callees = calls.getOrDefault(current, Set.of()).stream()
                    .sorted(Comparator.comparingInt(this::line))
                    .toList();
            for (MethodDeclaration next : callees) {
                if (next == method) {
                    Deque<String> steps = new ArrayDeque<>(List.of(method.getNameAsString()));
                    for (MethodDeclaration step = current; step != method; step = previous.get(step)) {
                        steps.addFirst(step.getNameAsString());
                    }
                    steps.addFirst(method.getNameAsString());
                    return String.join(ARROW, steps);
                }
                if (!previous.containsKey(next)) {
                    previous.put(next, current);
                    queue.addLast(next);
                }
            }
        }
        return method.getNameAsString();
    }

    /**
     * @return параметр, через который в метод приходит документ: узел дерева (JsonNode, org.w3c.dom.Node)
     * либо потоковый читатель (JsonParser, XMLStreamReader)
     */
    private Optional<DocumentParameter> documentParameter(MethodDeclaration method, CompilationUnit unit) {
        for (int index = 0; index < method.getParameters().size(); index++) {
            Parameter parameter = method.getParameter(index);
            String type = LocalTypes.typeName(parameter.getType());
            String written = parameter.getType().asString();
            if (TREE_TYPES.containsKey(type)) {
                return Optional.of(new DocumentParameter(index, parameter.getNameAsString(), TREE_TYPES.get(type), false));
            }
            if (READER_TYPES.containsKey(type)) {
                return Optional.of(new DocumentParameter(index, parameter.getNameAsString(), READER_TYPES.get(type), true));
            }
            if (AMBIGUOUS_TYPES.contains(type)) {
                Optional<String> format = formatOfImported(type, written, unit);
                if (format.isPresent()) {
                    return Optional.of(new DocumentParameter(index, parameter.getNameAsString(), format.get(), false));
                }
            }
        }
        return Optional.empty();
    }

    // org.w3c.dom.Node прямо в объявлении, import org.w3c.dom.Node, а без него - import org.w3c.dom.*.
    // Явный импорт важнее: import com.github.javaparser.ast.Node рядом с org.w3c.dom.* - узел дерева Java
    private Optional<String> formatOfImported(String type, String written, CompilationUnit unit) {
        if (written.indexOf(PACKAGE_SEPARATOR) > 0) {
            return formatOfPackage(written.substring(0, written.lastIndexOf(PACKAGE_SEPARATOR)));
        }
        List<ImportDeclaration> imports = unit.getImports().stream().filter(declaration -> !declaration.isStatic()).toList();
        Optional<ImportDeclaration> explicit = imports.stream()
                .filter(declaration -> !declaration.isAsterisk() && declaration.getName().getIdentifier().equals(type))
                .findFirst();
        if (explicit.isPresent()) {
            return explicit.get().getName().getQualifier().flatMap(qualifier -> formatOfPackage(qualifier.asString()));
        }
        return imports.stream()
                .filter(ImportDeclaration::isAsterisk)
                .map(declaration -> formatOfPackage(declaration.getNameAsString()))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<String> formatOfPackage(String packageName) {
        return Optional.ofNullable(DOCUMENT_PACKAGES.get(packageName));
    }

    private int line(MethodDeclaration method) {
        return method.getBegin().map(position -> position.line).orElse(0);
    }
}
