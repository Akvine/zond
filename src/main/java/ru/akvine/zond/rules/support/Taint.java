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
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.SourceFile;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Отслеживание данных извне: доходит ли значение, которым управляет не сам код, до выражения.
 * <p>
 * Значение прослеживается назад: от выражения к переменным, из которых оно собрано, от переменной -
 * ко всем присваиваниям ей, от параметра метода - к аргументам во всех местах, где метод вызывается.
 * Так находится путь "параметр контроллера - сервис - new File(...)", даже если звенья лежат в разных классах.
 * Порядок операторов внутри метода не учитывается: присваивание после использования тоже считается.
 * <p>
 * Источники: HTTP-запрос, загруженный файл, сообщение из очереди, ответ внешнего сервиса, а также свойство
 * объекта, в которое где-то в проекте записали такое значение (dto.setName(name) в контроллере и dto.getName()
 * в сервисе). Значение перестает считаться опасным, если оно приведено к числу или enum, прошло метод
 * очистки либо проверено на пути к использованию: по списку допустимых, по шаблону, сравнением с константой.
 */
public final class Taint {
    // Сколько вызовов можно пройти вверх от метода к тем, кто его вызывает, если правило не задало свое число
    private static final int DEFAULT_CALL_DEPTH = 4;

    private static final Set<String> REQUEST_ANNOTATIONS =
            Set.of("RequestParam", "PathVariable", "RequestHeader", "RequestPart", "CookieValue", "MatrixVariable");

    // Объект, собранный из запроса: данные клиента - это его поля
    private static final Set<String> BODY_ANNOTATIONS = Set.of("RequestBody", "ModelAttribute");

    // Spring связывает с запросом и параметры без аннотаций, если метод - обработчик запроса
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    // Методы, которые получают сообщения: их параметры приходят из очереди
    private static final Set<String> LISTENER_ANNOTATIONS = Set.of(
            "KafkaListener", "KafkaHandler", "RabbitListener", "RabbitHandler", "JmsListener", "SqsListener",
            "StreamListener", "PulsarListener", "MessageMapping", "ServiceActivator");
    private static final String STRING = "String";

    // @Pattern на параметре: значение проверено по шаблону еще до входа в метод
    private static final String PATTERN_ANNOTATION = "Pattern";

    // request.getParameter("name"), file.getOriginalFilename()
    private static final Set<String> REQUEST_METHODS =
            Set.of("getParameter", "getHeader", "getOriginalFilename", "getQueryString", "getPathInfo");

    // Содержимое файла, который загрузил клиент
    private static final Set<String> FILE_TYPES = Set.of("MultipartFile", "Part", "FilePart");
    private static final Set<String> FILE_CONTENT_METHODS = Set.of("getBytes", "getInputStream", "getResource");

    // Ответ другого сервиса: что он вернет, этот код не контролирует
    private static final Set<String> EXTERNAL_CALLS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "patchForObject", "bodyToMono",
            "bodyToFlux");
    private static final Set<String> HTTP_CLIENT_TYPES = Set.of("RestTemplate", "RestOperations", "RestClient", "WebClient");
    private static final String EXCHANGE = "exchange";

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

    // Приведение к числу, UUID или enum: если значение не подошло, будет исключение, а не инъекция
    private static final Set<String> CONVERTERS = Set.of(
            "parseInt", "parseLong", "parseDouble", "parseFloat", "parseShort", "parseByte", "parseBoolean",
            "fromString", "parseUnsignedInt", "parseUnsignedLong");
    private static final String VALUE_OF = "valueOf";

    // Проверки значения в условии: по списку допустимых, по шаблону, сравнением с константой
    private static final Set<String> VALIDATION_METHODS = Set.of(
            "contains", "containsKey", "matches", "equals", "equalsIgnoreCase", "isValid", "isAllowed");

    // Методы, результат которых собран из аргументов: String.format("%s", name), Paths.get(dir, name)
    private static final Set<String> PASSING_METHODS = Set.of(
            "format", "formatted", "concat", "join", "valueOf", "append", "replace", "replaceAll", "toString",
            "of", "get", "resolve", "decode", "requireNonNull", "orElse", "getOrDefault");

    private static final String GETTER_PREFIX = "get";

    private static WeakReference<List<SourceFile>> cachedSources = new WeakReference<>(null);
    private static Taint cached;

    /**
     * Откуда значение: от этого зависит, насколько находка достоверна
     */
    public enum Kind {
        REQUEST("данные запроса", Confidence.CONFIRMED),
        FILE("содержимое загруженного файла", Confidence.CONFIRMED),
        MESSAGE("сообщение из очереди", Confidence.PROBABLE),
        EXTERNAL("ответ внешнего сервиса", Confidence.PROBABLE),
        // Объект, из которого читают, может оказаться другим экземпляром того же типа
        PROPERTY("свойство, в которое записаны данные извне", Confidence.PROBABLE);

        private final String title;
        private final Confidence confidence;

        Kind(String title, Confidence confidence) {
            this.title = title;
            this.confidence = confidence;
        }
    }

    /**
     * Найденный источник
     *
     * @param text как источник записан в коде: имя параметра либо вызов
     */
    public record Source(String text, Kind kind) {

        public Confidence confidence() {
            return kind.confidence;
        }

        /**
         * @return true, если значение напрямую прислано извне: запросом, файлом или сообщением.
         * Ответ другого сервиса и значение, прошедшее через поле объекта, - ввод опосредованный
         */
        public boolean isDirectInput() {
            return kind == Kind.REQUEST || kind == Kind.FILE || kind == Kind.MESSAGE;
        }

        /**
         * @return источник вместе с его видом: "данные запроса: name", "сообщение из очереди: payload"
         */
        public String describe() {
            return kind.title + ": " + text;
        }

        // В тексте находки источник называют данными клиента; для остальных видов это нужно уточнить
        @Override
        public String toString() {
            return kind == Kind.REQUEST ? text : text + " - " + kind.title;
        }
    }

    /**
     * @param callable метод, в котором источник находится
     */
    private record Origin(String text, Node callable, Kind kind) {
    }

    private final CallGraph graph;
    // "Тип.свойство" -> значения, которые в него записывают по всему проекту
    private final Map<String, List<Expression>> writes = new HashMap<>();
    // Самый достоверный источник, найденный в этом потоке после последнего запроса уверенности
    private final ThreadLocal<Confidence> found = new ThreadLocal<>();
    // Предел глубины для правила, которое сейчас работает в этом потоке
    private final ThreadLocal<Integer> depthLimit = ThreadLocal.withInitial(() -> DEFAULT_CALL_DEPTH);

    private Taint(List<SourceFile> sources) {
        this.graph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                if (call.getArguments().size() != 1 || call.getScope().isEmpty()) {
                    continue;
                }
                String property = PropertyAccess.writtenProperty(call.getNameAsString());
                PropertyAccess.ownerType(call.getScope().get()).ifPresent(type ->
                        writes.computeIfAbsent(type + "." + property, key -> new ArrayList<>()).add(call.getArgument(0)));
            }
        }
    }

    public static synchronized Taint of(List<SourceFile> sources) {
        if (cachedSources.get() != sources) {
            cached = new Taint(sources);
            cachedSources = new WeakReference<>(sources);
        }
        return cached;
    }

    /**
     * @return откуда в выражение попадают данные извне; пусто, если их в выражении нет
     */
    public Optional<Source> findSource(Expression expression) {
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Optional<Origin> origin = source(expression, 0, visited);
        Optional<Node> here = Nodes.enclosingCallable(expression);
        Optional<Source> source = origin.map(result -> {
            boolean sameMethod = here.isPresent() && here.get() == result.callable();
            String method = result.callable() instanceof MethodDeclaration declaration
                    ? declaration.getNameAsString()
                    : "";
            String text = sameMethod || method.isEmpty() ? result.text() : result.text() + " из метода " + method;
            return new Source(text, result.kind());
        });
        source.ifPresent(result -> {
            Confidence best = found.get();
            if (best == null || result.confidence().isAtLeast(best)) {
                found.set(result.confidence());
            }
        });
        return source;
    }

    /**
     * Задает, на сколько вызовов вверх прослеживать значение для правила, работающего в этом потоке
     */
    public void limitDepth(int depth) {
        depthLimit.set(depth);
    }

    /**
     * @return уверенность находки по источникам, найденным с прошлого вызова этого метода: самый достоверный
     * из них; если источник не найден, находка - лишь подозрение
     */
    public Confidence takeConfidence() {
        Confidence best = found.get();
        found.remove();
        return best == null ? Confidence.SUSPICION : best;
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
        // Значение проверено на пути к этому месту: if (!ALLOWED.contains(sort)) throw ...
        if (isValidated(name)) {
            return Optional.empty();
        }
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

    // Проверка стоит на пути выполнения к этому использованию и относится к этой же переменной
    private boolean isValidated(NameExpr name) {
        String variable = name.getNameAsString();
        return Guards.isGuarded(name, condition -> condition.findAll(MethodCallExpr.class).stream()
                .filter(call -> VALIDATION_METHODS.contains(call.getNameAsString())
                        || SANITIZER.matcher(call.getNameAsString()).matches())
                .anyMatch(call -> call.findAll(NameExpr.class).stream()
                        .anyMatch(used -> used.getNameAsString().equals(variable))));
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
            return Optional.of(new Origin(parameter.getNameAsString(), method.get(), Kind.REQUEST));
        }
        if (Annotations.hasAny(method.get(), LISTENER_ANNOTATIONS)) {
            return Optional.of(new Origin(parameter.getNameAsString(), method.get(), Kind.MESSAGE));
        }
        if (depth >= depthLimit.get()) {
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
        Optional<Node> callable = Nodes.enclosingCallable(call);
        if (REQUEST_METHODS.contains(method)) {
            return callable.map(found -> new Origin(call.toString(), found, Kind.REQUEST));
        }
        if (SANITIZER.matcher(method).matches() || isConversion(call)) {
            return Optional.empty();
        }

        if (call.getScope().isPresent()) {
            Expression scope = Nodes.unwrap(call.getScope().get());
            Optional<String> scopeType = LocalTypes.typeOf(scope);
            if (FILE_CONTENT_METHODS.contains(method) && scopeType.filter(FILE_TYPES::contains).isPresent()) {
                return callable.map(found -> new Origin(call.toString(), found, Kind.FILE));
            }
            boolean isHttpCall = EXTERNAL_CALLS.contains(method)
                    || EXCHANGE.equals(method) && scopeType.filter(HTTP_CLIENT_TYPES::contains).isPresent();
            if (isHttpCall) {
                return callable.map(found -> new Origin(method + "(...)", found, Kind.EXTERNAL));
            }
            // dto.getName(), где dto - тело запроса
            if (method.startsWith(GETTER_PREFIX) && isRequestBody(scope)) {
                return callable.map(found -> new Origin(call.toString(), found, Kind.REQUEST));
            }
            // name.trim(), path.toLowerCase(), record.value(): результат несет то же, что и объект
            Optional<Origin> fromScope = source(scope, depth, visited);
            if (fromScope.isPresent()) {
                return fromScope;
            }
            // order.getComment(), если где-то в проекте было order.setComment(<данные извне>)
            Optional<Origin> written = sourceOfProperty(call, scopeType, depth, visited);
            if (written.isPresent()) {
                return written;
            }
        }
        return PASSING_METHODS.contains(method) ? firstSource(call.getArguments(), depth, visited) : Optional.empty();
    }

    // Integer.parseInt(value), UUID.fromString(value), Status.valueOf(value); String.valueOf(...) значение не меняет
    private boolean isConversion(MethodCallExpr call) {
        if (CONVERTERS.contains(call.getNameAsString())) {
            return true;
        }
        return VALUE_OF.equals(call.getNameAsString())
                && call.getScope().filter(scope -> !STRING.equals(scope.toString())).isPresent();
    }

    private Optional<Origin> sourceOfProperty(
            MethodCallExpr getter, Optional<String> ownerType, int depth, Set<Node> visited) {
        Optional<String> property = PropertyAccess.readProperty(getter);
        if (property.isEmpty() || ownerType.isEmpty() || depth >= depthLimit.get()) {
            return Optional.empty();
        }
        String key = ownerType.get() + "." + property.get();
        for (Expression written : writes.getOrDefault(key, List.of())) {
            Optional<Origin> origin = source(written, depth + 1, visited);
            if (origin.isPresent()) {
                // Сам источник достовернее, чем путь через объект: уверенность понижается
                return Optional.of(new Origin(
                        origin.get().text() + " через " + key, origin.get().callable(), Kind.PROPERTY));
            }
        }
        return Optional.empty();
    }

    private Optional<Origin> firstSource(List<Expression> expressions, int depth, Set<Node> visited) {
        for (Expression expression : expressions) {
            Optional<Origin> origin = source(expression, depth, visited);
            if (origin.isPresent()) {
                return origin;
            }
        }
        return Optional.empty();
    }

    private boolean isRequestParameter(Parameter parameter, MethodDeclaration method) {
        if (Annotations.has(parameter, PATTERN_ANNOTATION)) {
            return false;
        }
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
