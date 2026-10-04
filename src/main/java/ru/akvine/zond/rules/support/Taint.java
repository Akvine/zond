package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import ru.akvine.zond.models.SourceFile;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Отслеживание данных запроса: доходит ли значение, присланное клиентом, до выражения.
 * <p>
 * Значение прослеживается назад: от выражения к переменным, из которых оно собрано, от переменной -
 * ко всем присваиваниям ей, от параметра метода - к аргументам во всех местах, где метод вызывается.
 * Так находится путь "параметр контроллера - сервис - new File(...)", даже если звенья лежат в разных файлах.
 * Порядок операторов внутри метода не учитывается: присваивание после использования тоже считается источником.
 */
public final class Taint {
    // Сколько вызовов можно пройти вверх от метода к тем, кто его вызывает
    private static final int MAX_CALL_DEPTH = 4;

    private static final Set<String> REQUEST_ANNOTATIONS =
            Set.of("RequestParam", "PathVariable", "RequestHeader", "RequestPart", "CookieValue", "MatrixVariable");

    // Объект, собранный из запроса: данные клиента - это его поля
    private static final Set<String> BODY_ANNOTATIONS = Set.of("RequestBody", "ModelAttribute");

    // Spring связывает с запросом и параметры без аннотаций, если метод - обработчик запроса
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");
    private static final String STRING = "String";

    // request.getParameter("name"), file.getOriginalFilename()
    private static final Set<String> REQUEST_METHODS =
            Set.of("getParameter", "getHeader", "getOriginalFilename", "getQueryString", "getPathInfo");

    // Значение такого типа не может нести ни путь, ни адрес, ни кусок запроса
    private static final Set<String> SAFE_TYPES = Set.of(
            "int", "long", "short", "byte", "double", "float", "boolean", "char",
            "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character",
            "BigDecimal", "BigInteger", "UUID", "LocalDate", "LocalDateTime", "LocalTime", "Instant",
            "ZonedDateTime", "OffsetDateTime", "Duration");

    // Методы, после которых значение считается проверенным либо обезвреженным
    private static final Pattern SANITIZER = Pattern.compile(
            ".*(encode|escape|sanitize|normalize|cleanPath|canonical|validate|whitelist|allowed).*",
            Pattern.CASE_INSENSITIVE);

    // Методы, результат которых собран из аргументов: String.format("%s", name), Paths.get(dir, name)
    private static final Set<String> PASSING_METHODS = Set.of(
            "format", "formatted", "concat", "join", "valueOf", "append", "replace", "replaceAll", "toString",
            "of", "get", "resolve", "decode", "requireNonNull", "orElse", "getOrDefault");

    private static final String GETTER_PREFIX = "get";

    private final CallGraph graph;

    private Taint(CallGraph graph) {
        this.graph = graph;
    }

    public static Taint of(List<SourceFile> sources) {
        return new Taint(CallGraph.of(sources));
    }

    /**
     * @return откуда в выражение попадают данные клиента: имя параметра запроса либо вызов вроде
     * request.getParameter("name"); пусто, если данных клиента в выражении нет
     */
    public Optional<String> findSource(Expression expression) {
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Optional<Origin> origin = source(expression, 0, visited);
        Optional<Node> here = Nodes.enclosingCallable(expression);
        return origin.map(found -> {
            boolean sameMethod = here.isPresent() && here.get() == found.callable();
            String method = found.callable() instanceof MethodDeclaration declaration
                    ? declaration.getNameAsString()
                    : "";
            return sameMethod || method.isEmpty() ? found.text() : found.text() + " из метода " + method;
        });
    }

    /**
     * @param text     как источник записан в коде
     * @param callable метод, в котором он находится
     */
    private record Origin(String text, Node callable) {
    }

    private Optional<Origin> source(Expression expression, int depth, Set<Node> visited) {
        Expression value = Nodes.unwrap(expression);
        if (value.isLiteralExpr() || !visited.add(value)) {
            return Optional.empty();
        }
        if (LocalTypes.typeOf(value).filter(SAFE_TYPES::contains).isPresent()) {
            return Optional.empty();
        }

        if (value.isNameExpr()) {
            return sourceOfVariable(value.asNameExpr(), depth, visited);
        }
        if (value.isMethodCallExpr()) {
            return sourceOfCall(value.asMethodCallExpr(), depth, visited);
        }
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            return source(value.asBinaryExpr().getLeft(), depth, visited)
                    .or(() -> source(value.asBinaryExpr().getRight(), depth, visited));
        }
        if (value.isConditionalExpr()) {
            return source(value.asConditionalExpr().getThenExpr(), depth, visited)
                    .or(() -> source(value.asConditionalExpr().getElseExpr(), depth, visited));
        }
        if (value.isObjectCreationExpr()) {
            return firstSource(value.asObjectCreationExpr().getArguments(), depth, visited);
        }
        if (value.isArrayAccessExpr()) {
            return source(value.asArrayAccessExpr().getName(), depth, visited);
        }
        return Optional.empty();
    }

    private Optional<Origin> sourceOfVariable(NameExpr name, int depth, Set<Node> visited) {
        Optional<Node> declaration = LocalTypes.findDeclaration(name, name.getNameAsString());
        if (declaration.isEmpty()) {
            return Optional.empty();
        }
        if (declaration.get() instanceof Parameter parameter) {
            return sourceOfParameter(parameter, depth, visited);
        }
        if (!(declaration.get() instanceof VariableDeclarator variable) || isField(variable)) {
            return Optional.empty();
        }

        // for (String part : parts) - элемент несет то же, что и коллекция
        Optional<Origin> iterated = variable.getParentNode()
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof ForEachStmt)
                .flatMap(loop -> source(((ForEachStmt) loop).getIterable(), depth, visited));
        if (iterated.isPresent()) {
            return iterated;
        }

        Optional<Origin> initial = variable.getInitializer().flatMap(value -> source(value, depth, visited));
        if (initial.isPresent()) {
            return initial;
        }

        // Все присваивания этой переменной в методе, а не только последнее перед использованием
        Optional<Node> callable = Nodes.enclosingCallable(name);
        if (callable.isEmpty()) {
            return Optional.empty();
        }
        for (AssignExpr assign : callable.get().findAll(AssignExpr.class)) {
            boolean sameVariable = assign.getTarget().isNameExpr()
                    && LocalTypes.findDeclaration(assign.getTarget())
                    .filter(target -> target == variable)
                    .isPresent();
            if (sameVariable) {
                Optional<Origin> assigned = source(assign.getValue(), depth, visited);
                if (assigned.isPresent()) {
                    return assigned;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Origin> sourceOfParameter(Parameter parameter, int depth, Set<Node> visited) {
        Optional<MethodDeclaration> method = parameter.getParentNode()
                .filter(parent -> parent instanceof MethodDeclaration)
                .map(parent -> (MethodDeclaration) parent);
        // Параметр лямбды: откуда берутся его значения, по коду не проследить
        if (method.isEmpty()) {
            return Optional.empty();
        }

        if (isRequestParameter(parameter, method.get())) {
            return Optional.of(new Origin(parameter.getNameAsString(), method.get()));
        }
        if (depth >= MAX_CALL_DEPTH) {
            return Optional.empty();
        }

        // Обычный параметр: смотрим, что в него передают там, где метод вызывается
        int index = method.get().getParameters().indexOf(parameter);
        for (CallGraph.Call call : graph.callsTo(method.get())) {
            if (index < call.site().getArguments().size()) {
                Optional<Origin> passed = source(call.site().getArgument(index), depth + 1, visited);
                if (passed.isPresent()) {
                    return passed;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Origin> sourceOfCall(MethodCallExpr call, int depth, Set<Node> visited) {
        String method = call.getNameAsString();
        if (REQUEST_METHODS.contains(method)) {
            return Nodes.enclosingCallable(call).map(callable -> new Origin(call.toString(), callable));
        }
        if (SANITIZER.matcher(method).matches()) {
            return Optional.empty();
        }

        if (call.getScope().isPresent()) {
            Expression scope = Nodes.unwrap(call.getScope().get());
            // dto.getName(), где dto - тело запроса
            if (method.startsWith(GETTER_PREFIX) && isRequestBody(scope)) {
                return Nodes.enclosingCallable(call).map(callable -> new Origin(call.toString(), callable));
            }
            // name.trim(), path.toLowerCase(): результат несет то же, что и объект
            Optional<Origin> fromScope = source(scope, depth, visited);
            if (fromScope.isPresent()) {
                return fromScope;
            }
        }
        return PASSING_METHODS.contains(method) ? firstSource(call.getArguments(), depth, visited) : Optional.empty();
    }

    private Optional<Origin> firstSource(List<Expression> expressions, int depth, Set<Node> visited) {
        for (Expression expression : expressions) {
            Optional<Origin> found = source(expression, depth, visited);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private boolean isRequestParameter(Parameter parameter, MethodDeclaration method) {
        if (Annotations.hasAny(parameter, REQUEST_ANNOTATIONS)) {
            return true;
        }
        // Строковый параметр обработчика без аннотаций Spring берет из параметров запроса по имени
        return parameter.getAnnotations().isEmpty()
                && Annotations.hasAny(method, MAPPING_ANNOTATIONS)
                && STRING.equals(LocalTypes.typeName(parameter.getType()));
    }

    private boolean isRequestBody(Expression scope) {
        return scope.isNameExpr() && LocalTypes.findDeclaration(scope)
                .filter(node -> node instanceof Parameter parameter && Annotations.hasAny(parameter, BODY_ANNOTATIONS))
                .isPresent();
    }

    private boolean isField(VariableDeclarator variable) {
        return variable.getParentNode().filter(parent -> parent instanceof FieldDeclaration).isPresent();
    }
}
