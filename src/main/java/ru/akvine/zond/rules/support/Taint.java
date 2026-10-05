package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithStatements;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
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

    // Метод, который перекладывает данные из одного объекта в другой: toEntity(dto), map(source), convert(request).
    // Если тела у него нет (MapStruct, библиотека), считаем, что результат несет то же, что аргументы
    private static final Pattern MAPPING_METHOD = Pattern.compile("^(to|map|convert|from|as|copy)([A-Z].*)?$");

    // Вызов-утверждение отдельной строкой: validate(name), requireAllowed(sort) - не подошло, будет исключение
    private static final Pattern ASSERTING_METHOD = Pattern.compile(
            "^(validate|check|assert|require|ensure|verify).*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> CONSTRUCTOR_ANNOTATIONS = Set.of("AllArgsConstructor", "Data", "Value");

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
    // Классы проекта по простому имени: по ним выясняется, в какое свойство попадает аргумент конструктора
    private final Map<String, TypeDeclaration<?>> types = new HashMap<>();
    // Самый достоверный источник, найденный в этом потоке после последнего запроса уверенности
    private final ThreadLocal<Confidence> found = new ThreadLocal<>();
    // Предел глубины для правила, которое сейчас работает в этом потоке
    private final ThreadLocal<Integer> depthLimit = ThreadLocal.withInitial(() -> DEFAULT_CALL_DEPTH);

    private Taint(List<SourceFile> sources) {
        this.graph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            for (TypeDeclaration<?> type : source.unit().findAll(TypeDeclaration.class).stream()
                    .map(found -> (TypeDeclaration<?>) found).toList()) {
                types.putIfAbsent(type.getNameAsString(), type);
            }
        }
        for (SourceFile source : sources) {
            // new Order(comment), new OrderRecord(comment): аргумент конструктора попадает в свойство
            for (ObjectCreationExpr creation : source.unit().findAll(ObjectCreationExpr.class)) {
                String type = creation.getType().getNameAsString();
                List<String> properties = constructorProperties(type, creation.getArguments().size());
                for (int index = 0; index < properties.size(); index++) {
                    writes.computeIfAbsent(type + "." + properties.get(index), key -> new ArrayList<>())
                            .add(creation.getArgument(index));
                }
            }
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

    /**
     * Проверено ли значение на пути к этому использованию. В отличие от простого "проверка где-то выше",
     * учитывается смысл условия: if (ALLOWED.contains(sort)) { use(sort); } и
     * if (!ALLOWED.contains(sort)) throw ...; use(sort); - проверка, а if ("admin".equals(name)) return; use(name); - нет:
     * дальше идут как раз все остальные значения.
     */
    private boolean isValidated(NameExpr usage) {
        String variable = usage.getNameAsString();
        Node boundary = Nodes.enclosingCallable(usage).orElse(null);
        Node child = usage;
        Node parent = usage.getParentNode().orElse(null);
        while (parent != null && child != boundary) {
            if (parent instanceof IfStmt branch && branch.getCondition() != child
                    && passes(branch.getCondition(), branch.getThenStmt() == child, variable)) {
                return true;
            }
            if (parent instanceof ConditionalExpr branch && branch.getCondition() != child
                    && passes(branch.getCondition(), branch.getThenExpr() == child, variable)) {
                return true;
            }
            // check(x) && use(x): правая часть выполняется, только если левая истинна; с || - если ложна
            if (parent instanceof BinaryExpr binary && binary.getRight() == child
                    && (binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR)
                    && passes(binary.getLeft(), binary.getOperator() == BinaryExpr.Operator.AND, variable)) {
                return true;
            }
            if (parent instanceof NodeWithStatements<?> block && isCheckedEarlier(block.getStatements(), child, variable)) {
                return true;
            }
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    // Среди операторов до текущего есть выход при непрошедшей проверке либо вызов-утверждение
    private boolean isCheckedEarlier(List<Statement> statements, Node current, String variable) {
        for (Statement statement : statements) {
            if (statement == current) {
                return false;
            }
            if (statement instanceof IfStmt branch) {
                boolean exitsWhenTrue = exits(branch.getThenStmt());
                boolean exitsWhenFalse = branch.getElseStmt().filter(this::exits).isPresent();
                // Выполнение продолжилось - значит, условие приняло то значение, при котором выхода нет
                if (exitsWhenTrue && !exitsWhenFalse && passes(branch.getCondition(), false, variable)
                        || exitsWhenFalse && !exitsWhenTrue && passes(branch.getCondition(), true, variable)) {
                    return true;
                }
            }
            boolean isAssertion = statement instanceof ExpressionStmt expression
                    && expression.getExpression() instanceof MethodCallExpr call
                    && (ASSERTING_METHOD.matcher(call.getNameAsString()).matches()
                    || SANITIZER.matcher(call.getNameAsString()).matches())
                    && mentions(call, variable);
            if (isAssertion) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true, если из того, что условие приняло значение truth, следует, что проверка переменной пройдена
     */
    private boolean passes(Expression condition, boolean truth, String variable) {
        Expression value = Nodes.unwrap(condition);
        if (value instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return passes(unary.getExpression(), !truth, variable);
        }
        if (value instanceof BinaryExpr binary) {
            // a && b истинно - истинны оба; a || b ложно - ложны оба. В остальных случаях о части ничего не известно
            boolean bothKnown = binary.getOperator() == BinaryExpr.Operator.AND && truth
                    || binary.getOperator() == BinaryExpr.Operator.OR && !truth;
            return bothKnown
                    && (passes(binary.getLeft(), truth, variable) || passes(binary.getRight(), truth, variable));
        }
        return truth && value instanceof MethodCallExpr call
                && (VALIDATION_METHODS.contains(call.getNameAsString()) || SANITIZER.matcher(call.getNameAsString()).matches())
                && mentions(call, variable);
    }

    private boolean mentions(MethodCallExpr call, String variable) {
        return call.findAll(NameExpr.class).stream().anyMatch(used -> used.getNameAsString().equals(variable));
    }

    private boolean exits(Statement statement) {
        if (statement.isReturnStmt() || statement.isThrowStmt() || statement.isContinueStmt() || statement.isBreakStmt()) {
            return true;
        }
        return statement instanceof BlockStmt block && !block.getStatements().isEmpty()
                && exits(block.getStatements().get(block.getStatements().size() - 1));
    }

    private boolean isEnum(Parameter parameter) {
        return types.get(LocalTypes.typeName(parameter.getType())) instanceof EnumDeclaration;
    }

    /**
     * @return свойства, в которые попадают аргументы конструктора, по порядку; пусто, если класс не из проекта
     * либо подходящего конструктора не нашлось
     */
    private List<String> constructorProperties(String typeName, int arguments) {
        TypeDeclaration<?> type = types.get(typeName);
        if (type == null || arguments == 0) {
            return List.of();
        }
        if (type instanceof RecordDeclaration record) {
            return record.getParameters().size() == arguments
                    ? record.getParameters().stream().map(Parameter::getNameAsString).toList()
                    : List.of();
        }
        List<ConstructorDeclaration> constructors = type.getConstructors().stream()
                .filter(constructor -> constructor.getParameters().size() == arguments)
                .toList();
        if (constructors.size() == 1) {
            return constructors.get(0).getParameters().stream()
                    .map(parameter -> assignedField(constructors.get(0), parameter.getNameAsString()))
                    .toList();
        }
        // Конструктор Lombok: поля в порядке объявления
        List<String> fields = type.getFields().stream()
                .filter(field -> !field.isStatic())
                .flatMap(field -> field.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .toList();
        boolean isGenerated = constructors.isEmpty() && fields.size() == arguments
                && Annotations.hasAny(type, CONSTRUCTOR_ANNOTATIONS);
        return isGenerated ? fields : List.of();
    }

    // this.comment = text: параметр text попадает в поле comment; иначе считаем, что поле названо как параметр
    private String assignedField(ConstructorDeclaration constructor, String parameter) {
        for (AssignExpr assign : constructor.findAll(AssignExpr.class)) {
            boolean fromParameter = assign.getValue().isNameExpr()
                    && assign.getValue().asNameExpr().getNameAsString().equals(parameter);
            if (fromParameter) {
                return MethodCalls.receiverName(assign.getTarget());
            }
        }
        return parameter;
    }

    private Optional<Origin> sourceOfParameter(Parameter parameter, int depth, Set<Node> visited) {
        Optional<MethodDeclaration> method = parameter.getParentNode()
                .filter(parent -> parent instanceof MethodDeclaration)
                .map(parent -> (MethodDeclaration) parent);
        if (method.isEmpty()) {
            return sourceOfLambdaParameter(parameter, depth, visited);
        }

        if (isRequestParameter(parameter, method.get())) {
            return Optional.of(new Origin(parameter.getNameAsString(), method.get(), Kind.REQUEST));
        }
        if (Annotations.hasAny(method.get(), LISTENER_ANNOTATIONS) && !isEnum(parameter)) {
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

    // names.forEach(name -> ...), items.stream().map(item -> ...): параметр лямбды - элемент того, у чего вызван метод
    private Optional<Origin> sourceOfLambdaParameter(Parameter parameter, int depth, Set<Node> visited) {
        return parameter.getParentNode()
                .filter(parent -> parent instanceof LambdaExpr)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof MethodCallExpr)
                .flatMap(call -> ((MethodCallExpr) call).getScope())
                .flatMap(scope -> source(scope, depth, visited));
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
        if (PASSING_METHODS.contains(method)) {
            return firstSource(call.getArguments(), depth, visited);
        }
        boolean isOpaqueMapping = MAPPING_METHOD.matcher(method).matches() && !call.getArguments().isEmpty()
                && graph.targetsOf(call).stream().noneMatch(target -> target.getBody().isPresent());
        return isOpaqueMapping
                ? firstSource(call.getArguments(), depth, visited)
                        .map(origin -> new Origin(origin.text() + " через " + method + "(...)", origin.callable(), Kind.PROPERTY))
                : Optional.empty();
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
        // order.getComment() либо, у записи (record), order.comment()
        Optional<String> property = PropertyAccess.readProperty(getter)
                .or(() -> ownerType.filter(type -> types.get(type) instanceof RecordDeclaration)
                        .filter(type -> getter.getArguments().isEmpty())
                        .map(type -> getter.getNameAsString()));
        if (property.isEmpty() || ownerType.isEmpty() || depth >= depthLimit.get()) {
            return Optional.empty();
        }
        // Геттер может отдавать поле с другим именем: getComment() { return text; }
        List<String> names = new ArrayList<>(List.of(property.get()));
        returnedField(ownerType.get(), getter.getNameAsString()).ifPresent(names::add);
        for (String name : names) {
            String key = ownerType.get() + "." + name;
            for (Expression written : writes.getOrDefault(key, List.of())) {
                Optional<Origin> origin = source(written, depth + 1, visited);
                if (origin.isPresent()) {
                    // Сам источник достовернее, чем путь через объект: уверенность понижается
                    return Optional.of(new Origin(
                            origin.get().text() + " через " + key, origin.get().callable(), Kind.PROPERTY));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return поле, которое возвращает метод без параметров: для getComment() { return text; } - text
     */
    private Optional<String> returnedField(String typeName, String method) {
        TypeDeclaration<?> type = types.get(typeName);
        if (type == null) {
            return Optional.empty();
        }
        return type.getMethodsByName(method).stream()
                .filter(declaration -> declaration.getParameters().isEmpty())
                .flatMap(declaration -> declaration.findAll(ReturnStmt.class).stream())
                .flatMap(returned -> returned.getExpression().stream())
                .map(Nodes::unwrap)
                .filter(value -> value.isNameExpr() || value.isFieldAccessExpr())
                .map(MethodCalls::receiverName)
                .findFirst();
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
        // @Pattern проверил значение до входа в метод; значение enum не может быть ничем, кроме своих констант
        if (Annotations.has(parameter, PATTERN_ANNOTATION) || isEnum(parameter)) {
            return false;
        }
        // Объект, собранный из тела запроса, целиком состоит из данных клиента
        if (Annotations.hasAny(parameter, REQUEST_ANNOTATIONS) || Annotations.hasAny(parameter, BODY_ANNOTATIONS)) {
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
