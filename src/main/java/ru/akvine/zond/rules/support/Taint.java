package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
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

    // Источники, передающие методы и очистка - в справочнике taint-library.properties. Множества живые:
    // дополнения из своего файла (zond.taint.library) видны в них сразу

    // Spring MVC и JAX-RS (@QueryParam, @PathParam, @FormParam у Jersey, RESTEasy, Keycloak)
    private static final Set<String> REQUEST_ANNOTATIONS = TaintLibrary.names("request-annotations");

    // Объект, собранный из запроса: данные клиента - это его поля
    private static final Set<String> BODY_ANNOTATIONS = TaintLibrary.names("body-annotations");

    // Метод ресурса JAX-RS: параметр без аннотаций - тело запроса
    private static final Set<String> JAX_RS_BODY_METHODS = TaintLibrary.names("jax-rs-body-methods");

    // Spring связывает с запросом и параметры без аннотаций, если метод - обработчик запроса
    private static final Set<String> MAPPING_ANNOTATIONS = TaintLibrary.names("mapping-annotations");

    // Методы, которые получают сообщения: их параметры приходят из очереди
    private static final Set<String> LISTENER_ANNOTATIONS = TaintLibrary.names("listener-annotations");
    private static final String STRING = "String";

    // @Pattern на параметре: значение проверено по шаблону еще до входа в метод
    private static final String PATTERN_ANNOTATION = "Pattern";

    // request.getParameter("name"), file.getOriginalFilename()
    private static final Set<String> REQUEST_METHODS = TaintLibrary.names("request-methods");

    // request.getParameterValues("id"), request.getCookies(): эти имена есть и у других классов (getHeaders у
    // ответа другого сервиса), поэтому источником они считаются только у запроса сервлета
    private static final Set<String> SERVLET_REQUEST_METHODS = TaintLibrary.names("servlet-request-methods");
    private static final Set<String> SERVLET_REQUEST_TYPES = TaintLibrary.names("servlet-request-types");

    // Метка параметра при подсчете сводки метода: такой источник наружу не выходит
    private static final String PARAMETER_MARKER = "\u0000parameter:";
    private static final int NO_PARAMETER = -1;

    // Пакеты JDK, классы которых хранят значения, а не передают данные между частями программы
    private static final List<String> JDK_PACKAGES = List.of(
            "java.lang.", "java.util.", "java.sql.", "java.time.", "java.io.", "java.net.", "java.text.");
    private static final Pattern REQUEST_VARIABLE = Pattern.compile("(?i)(http)?(servlet)?req(uest)?");
    private static final String REQUEST_TYPE_WORD = "Request";

    // sql.append(name), params.put("name", name), names.add(name): объект накапливает то, что в него положили
    private static final Set<String> CONTAINER_WRITES = Set.of(
            "append", "add", "addAll", "put", "putAll", "putIfAbsent", "insert", "set", "push", "offer",
            "addFirst", "addLast", "addElement", "setProperty");
    // Методы, которые кладут значение под ключ или номер: само значение - второй аргумент
    private static final Set<String> KEYED_WRITES = Set.of("put", "putIfAbsent", "setProperty", "set", "insert");
    private static final Set<String> KEYED_READS = Set.of("get", "getOrDefault", "getProperty");
    private static final String APPEND = "append";
    private static final String ADD = "add";
    private static final String REMOVE = "remove";
    private static final String GET = "get";
    private static final String GET_OR_DEFAULT = "getOrDefault";
    private static final Set<String> LIST_READS = Set.of("get", "size", "isEmpty", "contains", "indexOf");

    // Содержимое файла, который загрузил клиент
    private static final Set<String> FILE_TYPES = TaintLibrary.names("file-types");
    private static final Set<String> FILE_CONTENT_METHODS = TaintLibrary.names("file-content-methods");

    // Ответ другого сервиса: что он вернет, этот код не контролирует
    private static final Set<String> EXTERNAL_CALLS = TaintLibrary.names("external-calls");
    private static final Set<String> HTTP_CLIENT_TYPES = TaintLibrary.names("http-client-types");
    private static final String EXCHANGE = "exchange";

    // Значение такого типа не может нести ни путь, ни адрес, ни кусок запроса
    private static final Set<String> SAFE_TYPES = TaintLibrary.names("safe-types");

    // Слова в имени метода, после которого значение считается проверенным либо обезвреженным
    private static final String SANITIZER_WORDS = "sanitizer-words";
    // Base64 и hex значение не обезвреживают: decodeBase64(encodeBase64(name)) вернет его как было
    private static final String REVERSIBLE_ENCODING_WORDS = "reversible-encoding-words";
    // Экранирование для HTML: в ответе оно спасает, а в SQL, пути к файлу или команде ничего не меняет
    private static final String HTML_ESCAPE_WORDS = "html-escape-words";
    private static final String SQL_ESCAPE_WORDS = "sql-escape-words";

    // Приведение к числу, UUID или enum: если значение не подошло, будет исключение, а не инъекция
    private static final Set<String> CONVERTERS = TaintLibrary.names("converters");
    private static final String VALUE_OF = "valueOf";

    // Проверки значения в условии: по списку допустимых, по шаблону, сравнением с константой
    private static final Set<String> VALIDATION_METHODS = TaintLibrary.names("validation-methods");

    // Методы, результат которых собран из аргументов: String.format("%s", name), Paths.get(dir, name)
    private static final Set<String> PASSING_METHODS = TaintLibrary.names("passing-methods");

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
     * Куда попадает значение: от этого зависит, какое экранирование его обезвреживает. В сессию (SESSION)
     * значение кладут как проверенное сервером: экранирование для HTML или SQL этого не дает
     */
    public enum Sink {
        ANY, SQL, HTML, PATH, COMMAND, SESSION
    }

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
    private final ProjectClasses classes;
    // Узлы дерева сравниваются по содержимому, а два одинаковых по тексту метода - разные методы.
    // Отслеживанием пользуются несколько правил сразу, каждое в своем потоке
    private final Map<MethodDeclaration, Optional<Handlers.Handler>> handlers =
            Collections.synchronizedMap(new IdentityHashMap<>());
    // "Тип.свойство" -> значения, которые в него записывают по всему проекту
    private final Map<String, List<Expression>> writes = new HashMap<>();
    // Классы проекта по простому имени: по ним выясняется, в какое свойство попадает аргумент конструктора
    private final Map<String, TypeDeclaration<?>> types = new HashMap<>();
    // Самый достоверный источник, найденный в этом потоке после последнего запроса уверенности
    private final ThreadLocal<Confidence> found = new ThreadLocal<>();
    // Предел глубины для правила, которое сейчас работает в этом потоке
    private final ThreadLocal<Integer> depthLimit = ThreadLocal.withInitial(() -> DEFAULT_CALL_DEPTH);
    // Куда попадает значение у правила, которое сейчас работает в этом потоке
    private final ThreadLocal<Sink> sink = ThreadLocal.withInitial(() -> Sink.ANY);
    // Достигающие определения по методам: считаются при первом обращении
    private final Map<Node, ReachingDefinitions> definitions = new IdentityHashMap<>();
    // Сводки методов: метод -> место назначения -> что доходит до результата
    private final Map<MethodDeclaration, Map<Sink, ReturnSummary>> summaries = new IdentityHashMap<>();
    // Сводки, которые сейчас считаются в этом потоке: рекурсивный вызов о результате ничего не говорит
    private final ThreadLocal<Set<MethodDeclaration>> summarizing =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    // Метод, сводка которого считается, и его параметр, который сейчас считается меченым
    private final ThreadLocal<Deque<Marked>> marked = ThreadLocal.withInitial(ArrayDeque::new);
    // Сводки параметров: параметр -> место назначения -> запас глубины -> что в него передают вызывающие
    private final Map<Parameter, Map<Sink, Map<Integer, Optional<Origin>>>> parameterSummaries = new IdentityHashMap<>();
    // Параметры, сводка которых сейчас считается в этом потоке
    private final ThreadLocal<Set<Parameter>> tracedParameters =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    // Сколько раз в этом потоке поиск оборвала защита от рекурсии: ответ, при подсчете которого она сработала,
    // зависит от того, откуда спросили, и в сводку не идет
    private final ThreadLocal<int[]> guardHits = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * Что доходит до результата метода
     *
     * @param intrinsic  источник в самом методе: return request.getParameter("name")
     * @param parameters номера параметров, значение которых доходит до return
     */
    private record ReturnSummary(Optional<Origin> intrinsic, Set<Integer> parameters) {
        private static final ReturnSummary EMPTY = new ReturnSummary(Optional.empty(), Set.of());
    }

    /**
     * @param parameter номер меченого параметра; NO_PARAMETER - все параметры чисты
     */
    private record Marked(MethodDeclaration method, int parameter) {
    }

    private Taint(List<SourceFile> sources) {
        this.graph = CallGraph.of(sources);
        this.classes = ProjectClasses.of(sources);
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
                // calendar.setTime(date) в одном месте ничего не говорит о calendar.getTime() в другом: объекты
                // библиотек живут внутри метода, а через свойства данные передают только свои классы
                String property = PropertyAccess.writtenProperty(call.getNameAsString());
                PropertyAccess.ownerType(call.getScope().get()).filter(type -> !isLibraryObject(type)).ifPresent(type ->
                        writes.computeIfAbsent(type + "." + property, key -> new ArrayList<>()).add(call.getArgument(0)));
            }
        }
    }

    // Calendar, Date, Timestamp, StringBuilder: свой класс с таким именем проект объявил бы в исходниках.
    // Имя сверяется с JDK, а не с разрешенным типом: com.ibm.icu.util.Calendar устроен так же, как java.util.Calendar
    private boolean isLibraryObject(String type) {
        return !types.containsKey(type) && JDK_PACKAGES.stream().anyMatch(prefix -> {
            try {
                Class.forName(prefix + type, false, ClassLoader.getPlatformClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        });
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
     * @return присваивания, которые доходят до этого чтения переменной по ходу выполнения метода; пусто, если
     * переменная не локальная либо порядок выполнения разобрать не удалось
     */
    public Optional<Set<Node>> definitionsReaching(NameExpr usage) {
        return reachingDefinitions(usage);
    }

    /**
     * @return номера аргументов вызова своего метода, значения которых доходят до его результата: для
     * doSomething(request, param), который возвращает константу, - пустое множество. Пусто (Optional), если
     * у метода нет тела в проекте либо он сам возвращает данные извне
     */
    public Optional<Set<Integer>> returnedArguments(MethodCallExpr call) {
        List<MethodDeclaration> targets = graph.targetsOf(call);
        if (targets.isEmpty() || targets.stream().anyMatch(target -> target.getBody().isEmpty())) {
            return Optional.empty();
        }
        Set<Integer> arguments = new HashSet<>();
        for (MethodDeclaration target : targets) {
            ReturnSummary summary = summaryOf(target);
            if (summary.intrinsic().isPresent()) {
                return Optional.empty();
            }
            for (int index : summary.parameters()) {
                boolean varArgs = index == target.getParameters().size() - 1 && target.getParameter(index).isVarArgs();
                int last = varArgs ? call.getArguments().size() - 1 : index;
                for (int argument = index; argument <= last && argument < call.getArguments().size(); argument++) {
                    arguments.add(argument);
                }
            }
        }
        return Optional.of(arguments);
    }

    /**
     * Задает, на сколько вызовов вверх прослеживать значение для правила, работающего в этом потоке
     */
    public void limitDepth(int depth) {
        depthLimit.set(depth);
    }

    /**
     * Задает, куда попадает значение у правила, работающего в этом потоке: SQL, HTML, путь, команда
     */
    public void useSink(Sink kind) {
        sink.set(kind);
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
            // (7 * 18) + num > 200 ? "safe" : param - условие известно, вторая ветка не выполнится
            ConditionalExpr branch = value.asConditionalExpr();
            Optional<Boolean> known = ConstantValues.condition(branch.getCondition());
            if (known.isPresent()) {
                return source(known.get() ? branch.getThenExpr() : branch.getElseExpr(), depth, visited);
            }
            return source(branch.getThenExpr(), depth, visited)
                    .or(() -> source(branch.getElseExpr(), depth, visited));
        }
        if (value.isCastExpr()) {
            return source(value.asCastExpr().getExpression(), depth, visited);
        }
        if (value.isObjectCreationExpr()) {
            return firstSource(value.asObjectCreationExpr().getArguments(), depth, visited);
        }
        if (value.isArrayAccessExpr()) {
            return source(value.asArrayAccessExpr().getName(), depth, visited);
        }
        // String[] args = {"sh", "-c", command}: массив несет то же, что его элементы
        if (value.isArrayInitializerExpr()) {
            return firstSource(value.asArrayInitializerExpr().getValues(), depth, visited);
        }
        if (value.isArrayCreationExpr()) {
            return value.asArrayCreationExpr().getInitializer()
                    .flatMap(initializer -> firstSource(initializer.getValues(), depth, visited));
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
        // Только те присваивания, что доходят до этого места по ходу выполнения метода
        Optional<Set<Node>> reaching = reachingDefinitions(name);
        if (reaching.isPresent()) {
            for (Node definition : reaching.get()) {
                Optional<Origin> origin = sourceOfDefinition(definition, depth, visited);
                if (origin.isPresent()) {
                    return origin;
                }
            }
            return declaration.get() instanceof VariableDeclarator variable && !isField(variable)
                    ? Nodes.enclosingCallable(name).flatMap(callable -> sourceOfContents(variable, callable, depth, visited))
                    : Optional.empty();
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

        Optional<Node> callable = Nodes.enclosingCallable(name);
        List<AssignExpr> assignments = callable.stream()
                .flatMap(found -> found.findAll(AssignExpr.class).stream())
                .filter(assign -> assign.getTarget().isNameExpr() && assign.getOperator() == AssignExpr.Operator.ASSIGN
                        && LocalTypes.findDeclaration(assign.getTarget()).filter(target -> target == variable).isPresent())
                .toList();

        boolean initializerOverwritten = variable.findAncestor(Statement.class)
                .filter(statement -> isOverwritten(statement, assignments, name))
                .isPresent();
        Optional<Origin> initial = initializerOverwritten
                ? Optional.empty()
                : variable.getInitializer().flatMap(value -> source(value, depth, visited));
        if (initial.isPresent()) {
            return initial;
        }

        // Все присваивания этой переменной в методе, а не только последнее перед использованием
        if (callable.isEmpty()) {
            return Optional.empty();
        }
        for (AssignExpr assign : callable.get().findAll(AssignExpr.class)) {
            boolean sameVariable = assign.getTarget().isNameExpr()
                    && LocalTypes.findDeclaration(assign.getTarget())
                    .filter(target -> target == variable)
                    .isPresent();
            boolean overwritten = assign.getParentNode()
                    .filter(Statement.class::isInstance)
                    .filter(statement -> isOverwritten((Statement) statement, assignments, name))
                    .isPresent();
            // case 'A': bar = param при switch по известному 'B' не выполнится; bar = param; bar = "safe"; use(bar)
            if (sameVariable && !overwritten && !ConstantValues.isUnreachable(assign, callable.get())) {
                Optional<Origin> assigned = source(assign.getValue(), depth, visited);
                if (assigned.isPresent()) {
                    return assigned;
                }
            }
        }
        return sourceOfContents(variable, callable.get(), depth, visited);
    }

    // Анализ считается один раз на метод и нужен всем правилам сканирования
    private Optional<Set<Node>> reachingDefinitions(NameExpr usage) {
        Optional<Node> callable = Nodes.enclosingCallable(usage);
        if (callable.isEmpty()) {
            return Optional.empty();
        }
        ReachingDefinitions analysis;
        synchronized (definitions) {
            analysis = definitions.get(callable.get());
        }
        if (analysis == null) {
            analysis = ReachingDefinitions.of(callable.get());
            synchronized (definitions) {
                definitions.putIfAbsent(callable.get(), analysis);
            }
        }
        return analysis.definitionsOf(usage);
    }

    // Объявление с начальным значением, присваивание, параметр метода или лямбды, переменная цикла for-each
    private Optional<Origin> sourceOfDefinition(Node definition, int depth, Set<Node> visited) {
        if (definition instanceof Parameter parameter) {
            return sourceOfParameter(parameter, depth, visited);
        }
        if (definition instanceof AssignExpr assignment) {
            return source(assignment.getValue(), depth, visited);
        }
        if (definition instanceof VariableDeclarator variable) {
            Optional<Node> loop = variable.getParentNode().flatMap(Node::getParentNode).filter(ForEachStmt.class::isInstance);
            if (loop.isPresent()) {
                return source(((ForEachStmt) loop.get()).getIterable(), depth, visited);
            }
            return variable.getInitializer().flatMap(value -> source(value, depth, visited));
        }
        return Optional.empty();
    }

    /**
     * bar = map.get("keyB"); bar = map.get("keyA"); use(bar): до использования значение безусловно перезаписано
     * следующим оператором того же блока - первое присваивание до него не доходит
     */
    private boolean isOverwritten(Statement statement, List<AssignExpr> assignments, NameExpr usage) {
        if (!(statement.getParentNode().orElse(null) instanceof BlockStmt block) || statement.isAncestorOf(usage)) {
            return false;
        }
        int index = indexOf(block, statement);
        for (AssignExpr later : assignments) {
            if (!(later.getParentNode().orElse(null) instanceof ExpressionStmt laterStatement)
                    || laterStatement.getParentNode().orElse(null) != block
                    || indexOf(block, laterStatement) <= index
                    || later.getValue().findAll(NameExpr.class).stream()
                    .anyMatch(used -> used.getNameAsString().equals(usage.getNameAsString()))) {
                continue;
            }
            // Использование стоит после перезаписи, а не между двумя присваиваниями
            boolean usedAfter = laterStatement.getEnd().flatMap(end -> usage.getBegin().map(begin -> begin.isAfter(end)))
                    .orElse(false);
            if (usedAfter) {
                return true;
            }
        }
        return false;
    }

    private int indexOf(BlockStmt block, Statement statement) {
        for (int index = 0; index < block.getStatements().size(); index++) {
            if (block.getStatement(index) == statement) {
                return index;
            }
        }
        return -1;
    }

    // StringBuilder sql = new StringBuilder(); sql.append(name) - объект несет то, что в него положили
    private Optional<Origin> sourceOfContents(VariableDeclarator variable, Node callable, int depth, Set<Node> visited) {
        for (MethodCallExpr write : writesInto(variable, callable)) {
            for (Expression stored : storedValues(write)) {
                Optional<Origin> origin = source(stored, depth, visited);
                if (origin.isPresent()) {
                    return origin;
                }
            }
        }
        return Optional.empty();
    }

    // sql.append(a).append(b): обе записи - в sql
    private List<MethodCallExpr> writesInto(VariableDeclarator variable, Node callable) {
        return callable.findAll(MethodCallExpr.class).stream()
                .filter(call -> CONTAINER_WRITES.contains(call.getNameAsString()))
                .filter(call -> receiverRoot(call).filter(root -> LocalTypes.findDeclaration(root)
                        .filter(found -> found == variable).isPresent()).isPresent())
                .filter(call -> !ConstantValues.isUnreachable(call, callable))
                .toList();
    }

    private Optional<NameExpr> receiverRoot(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope().map(Nodes::unwrap);
        while (scope.isPresent() && scope.get() instanceof MethodCallExpr inner
                && CONTAINER_WRITES.contains(inner.getNameAsString())) {
            scope = inner.getScope().map(Nodes::unwrap);
        }
        return scope.filter(Expression::isNameExpr).map(Expression::asNameExpr);
    }

    // put(key, value), set(index, value), add(index, value) - значение вторым; append(chars, offset, length) - первым
    private List<Expression> storedValues(MethodCallExpr write) {
        List<Expression> arguments = write.getArguments();
        if (arguments.isEmpty()) {
            return List.of();
        }
        boolean valueSecond = arguments.size() >= 2 && (KEYED_WRITES.contains(write.getNameAsString())
                || ADD.equals(write.getNameAsString()));
        if (APPEND.equals(write.getNameAsString()) || !valueSecond) {
            return List.of(arguments.get(0));
        }
        return List.of(arguments.get(1));
    }

    /**
     * Чтение по известному ключу из коллекции, собранной в этом же методе: map.get("keyB") несет только то, что
     * положили под "keyB"; list.get(1) после add("safe"), add(param), remove(0) - только param.
     *
     * @return пусто, если так определить нельзя и коллекция считается целиком
     */
    private Optional<Optional<Origin>> sourceOfKeyedRead(MethodCallExpr read, int depth, Set<Node> visited) {
        if (!KEYED_READS.contains(read.getNameAsString()) || read.getArguments().isEmpty()) {
            return Optional.empty();
        }
        Optional<VariableDeclarator> collection = read.getScope()
                .map(Nodes::unwrap)
                .filter(Expression::isNameExpr)
                .flatMap(LocalTypes::findDeclaration)
                .filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast)
                .filter(variable -> !isField(variable))
                .filter(variable -> variable.getInitializer().map(Nodes::unwrap)
                        .filter(initializer -> initializer.isObjectCreationExpr()
                                && initializer.asObjectCreationExpr().getArguments().isEmpty())
                        .isPresent());
        Optional<Node> callable = Nodes.enclosingCallable(read);
        Optional<Object> key = ConstantValues.evaluate(read.getArgument(0));
        if (collection.isEmpty() || callable.isEmpty() || key.isEmpty()) {
            return Optional.empty();
        }
        List<MethodCallExpr> writes = writesInto(collection.get(), callable.get());
        boolean keyed = writes.stream().anyMatch(write -> KEYED_WRITES.contains(write.getNameAsString())
                && !"set".equals(write.getNameAsString()) && !"insert".equals(write.getNameAsString()));
        if (keyed) {
            for (MethodCallExpr write : writes) {
                Optional<Object> written = write.getArguments().isEmpty()
                        ? Optional.empty()
                        : ConstantValues.evaluate(write.getArgument(0));
                boolean sameKey = written.isEmpty() || ConstantValues.same(written.get(), key.get());
                if (!sameKey) {
                    continue;
                }
                for (Expression stored : storedValues(write)) {
                    Optional<Origin> origin = source(stored, depth, visited);
                    if (origin.isPresent()) {
                        return Optional.of(origin);
                    }
                }
            }
            // getOrDefault("key", fallback): если ключа не было, вернется запасное значение
            return Optional.of(read.getArguments().size() > 1
                    ? source(read.getArgument(1), depth, visited)
                    : Optional.empty());
        }
        return listElement(collection.get(), read, key.get())
                .map(element -> source(element, depth, visited));
    }

    // list.add(...), list.remove(0) отдельными операторами подряд, без циклов и ветвлений - и list.get(i) после них
    private Optional<Expression> listElement(VariableDeclarator list, MethodCallExpr read, Object index) {
        Optional<Statement> declaration = list.findAncestor(Statement.class);
        Optional<BlockStmt> block = declaration.flatMap(statement -> statement.getParentNode())
                .filter(BlockStmt.class::isInstance)
                .map(BlockStmt.class::cast);
        if (!(index instanceof Long position) || block.isEmpty() || !GET.equals(read.getNameAsString())) {
            return Optional.empty();
        }
        List<Expression> elements = new ArrayList<>();
        boolean started = false;
        for (Statement statement : block.get().getStatements()) {
            if (statement == declaration.get()) {
                started = true;
                continue;
            }
            if (!started) {
                continue;
            }
            List<NameExpr> uses = statement.findAll(NameExpr.class).stream()
                    .filter(name -> LocalTypes.findDeclaration(name).filter(found -> found == list).isPresent())
                    .toList();
            if (statement.isAncestorOf(read)) {
                // До чтения в этом же операторе со списком ничего не делают
                boolean onlyRead = uses.size() == 1 && read.getScope().filter(scope -> scope == uses.get(0)).isPresent();
                return onlyRead && position >= 0 && position < elements.size()
                        ? Optional.of(elements.get(position.intValue()))
                        : Optional.empty();
            }
            // String first = list.get(0), list.size(): чтение список не меняет
            boolean onlyReads = uses.stream().allMatch(use -> use.getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr call && LIST_READS.contains(call.getNameAsString())
                            && call.getScope().filter(scope -> scope == use).isPresent())
                    .isPresent());
            if (onlyReads) {
                continue;
            }
            if (uses.size() != 1 || !applyListChange(statement, uses.get(0), elements)) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private boolean applyListChange(Statement statement, NameExpr list, List<Expression> elements) {
        if (!(statement instanceof ExpressionStmt expression) || !(expression.getExpression() instanceof MethodCallExpr call)
                || call.getScope().filter(scope -> scope == list).isEmpty()) {
            return false;
        }
        if (ADD.equals(call.getNameAsString()) && call.getArguments().size() == 1) {
            elements.add(call.getArgument(0));
            return true;
        }
        if (REMOVE.equals(call.getNameAsString()) && call.getArguments().size() == 1) {
            Optional<Object> removed = ConstantValues.evaluate(call.getArgument(0));
            if (removed.isPresent() && removed.get() instanceof Long position
                    && position >= 0 && position < elements.size()) {
                elements.remove(position.intValue());
                return true;
            }
        }
        return false;
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

    // validator.validate(dto); ... dto.getPath(): объект целиком отдан проверке выше по коду. Условие о его
    // свойстве ("OK".equals(result.getStatus())) другие свойства не проверяет и не считается
    private boolean isValidatedObject(NameExpr owner) {
        String variable = owner.getNameAsString();
        int line = owner.getBegin().map(position -> position.line).orElse(Integer.MAX_VALUE);
        return Nodes.enclosingCallable(owner).stream()
                .flatMap(callable -> callable.findAll(ExpressionStmt.class).stream())
                .filter(statement -> statement.getBegin().map(position -> position.line).orElse(Integer.MAX_VALUE) < line)
                .map(ExpressionStmt::getExpression)
                .filter(expression -> expression instanceof MethodCallExpr)
                .map(expression -> (MethodCallExpr) expression)
                .filter(call -> ASSERTING_METHOD.matcher(call.getNameAsString()).matches()
                        || isSanitizer(call.getNameAsString()))
                .anyMatch(call -> call.getArguments().stream()
                        .anyMatch(argument -> argument instanceof NameExpr name && name.getNameAsString().equals(variable)));
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
                    || isSanitizer(call.getNameAsString()))
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
                && (VALIDATION_METHODS.contains(call.getNameAsString()) || isSanitizer(call.getNameAsString()))
                && mentions(call, variable);
    }

    // Проверяется само значение, а не его свойство: theCookie.getName().equals("id") ничего не говорит о
    // theCookie.getValue(), а name.trim().equals("id") - о name говорит
    private boolean mentions(MethodCallExpr call, String variable) {
        return call.findAll(NameExpr.class).stream()
                .filter(used -> used.getNameAsString().equals(variable))
                .anyMatch(used -> !isGetterScope(used));
    }

    private boolean isGetterScope(NameExpr name) {
        return name.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr getter
                        && getter.getScope().filter(scope -> scope == name).isPresent()
                        && PropertyAccess.readProperty(getter).isPresent())
                .isPresent();
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
        int index = method.get().getParameters().indexOf(parameter);
        // Считается сводка этого метода: меченым считается только один параметр, остальные чисты
        Marked current = marked.get().peek();
        if (current != null && current.method() == method.get()) {
            return index == current.parameter()
                    ? Optional.of(new Origin(PARAMETER_MARKER + index, method.get(), Kind.REQUEST))
                    : Optional.empty();
        }
        if (depth >= depthLimit.get()) {
            return Optional.empty();
        }
        if (current != null) {
            return sourceFromCallers(method.get(), index, depth, visited);
        }
        // Что приходит в параметр, считается один раз для места назначения и запаса глубины: метод, который
        // вызывают из многих мест, не обходит своих вызывающих заново для каждого чтения параметра. Сводка
        // считается со своим множеством пройденных узлов, поэтому не зависит от того, кто спросил первым
        Map<Integer, Optional<Origin>> summary = parameterSummary(parameter);
        int budget = depthLimit.get() - depth;
        synchronized (summary) {
            Optional<Origin> ready = summary.get(budget);
            if (ready != null) {
                return ready;
            }
        }
        if (!tracedParameters.get().add(parameter)) {
            guardHits.get()[0]++;
            return Optional.empty();
        }
        int hitsBefore = guardHits.get()[0];
        Optional<Origin> result;
        try {
            result = sourceFromCallers(method.get(), index, depth, Collections.newSetFromMap(new IdentityHashMap<>()));
        } finally {
            tracedParameters.get().remove(parameter);
        }
        // Рекурсия оборвала поиск: такой ответ верен только для этого пути и в сводку не идет
        if (guardHits.get()[0] == hitsBefore) {
            synchronized (summary) {
                summary.put(budget, result);
            }
        }
        return result;
    }

    // Обычный параметр: смотрим, что в него передают там, где метод вызывается
    private Optional<Origin> sourceFromCallers(MethodDeclaration method, int index, int depth, Set<Node> visited) {
        for (CallGraph.Call call : graph.callsTo(method)) {
            if (index < call.site().getArguments().size()) {
                Optional<Origin> passed = source(call.site().getArgument(index), depth + 1, visited);
                if (passed.isPresent()) {
                    return passed;
                }
            }
        }
        return Optional.empty();
    }

    private Map<Integer, Optional<Origin>> parameterSummary(Parameter parameter) {
        Sink target = sink.get();
        synchronized (parameterSummaries) {
            return parameterSummaries.computeIfAbsent(parameter, key -> new EnumMap<>(Sink.class))
                    .computeIfAbsent(target, key -> new HashMap<>());
        }
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
        // getHeader(mediaType) без получателя - свой метод класса, а не чтение запроса: кроме обертки над
        // запросом (class XssRequestWrapper extends HttpServletRequestWrapper)
        boolean readsRequest = call.getScope().isPresent() || isRequestWrapper(call);
        if (REQUEST_METHODS.contains(method) && readsRequest
                || SERVLET_REQUEST_METHODS.contains(method) && call.getScope().filter(this::isServletRequest).isPresent()) {
            return callable.map(found -> new Origin(call.toString(), found, Kind.REQUEST));
        }
        // Base64.decodeBase64(...), Hex.encodeHex(...) и экранирование не для этого места: значение то же
        boolean reversible = TaintLibrary.containsWord(REVERSIBLE_ENCODING_WORDS, method);
        if (isSanitizer(method) && !protects(method) || reversible) {
            return firstSource(call.getArguments(), depth, visited);
        }
        if (isSanitizer(method) || isConversion(call)) {
            return Optional.empty();
        }
        Optional<Optional<Origin>> keyed = sourceOfKeyedRead(call, depth, visited);
        if (keyed.isPresent()) {
            return keyed.get();
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
            // order.getComment(), если где-то в проекте было order.setComment(<данные извне>). Объект, который
            // выше проверили целиком - downloadFileDtoValidator.validate(dto), - уже не несет непроверенных данных
            if (scope instanceof NameExpr owner && isValidatedObject(owner)) {
                return Optional.empty();
            }
            Optional<Origin> written = sourceOfProperty(call, scopeType, depth, visited);
            if (written.isPresent()) {
                return written;
            }
        }
        Optional<Origin> returned = sourceOfReturned(call, depth, visited);
        if (returned.isPresent()) {
            return returned;
        }
        // externalLoginKeys.get(key), cache.get(id): по ключу клиента находится серверный объект, а не его данные.
        // Paths.get(dir, name) у класса собирает значение из аргументов; getOrDefault(key, fallback) отдает fallback
        if (KEYED_READS.contains(method) && call.getScope().filter(scope -> !isTypeReference(scope)).isPresent()) {
            return method.equals(GET_OR_DEFAULT) && call.getArguments().size() > 1
                    ? source(call.getArgument(1), depth, visited)
                    : Optional.empty();
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

    // bar = doSomething(request, param), path = buildPath(name): что возвращает свой метод - по его сводке.
    // Сводка считается один раз на метод: какие параметры доходят до результата и нет ли источника внутри
    private Optional<Origin> sourceOfReturned(MethodCallExpr call, int depth, Set<Node> visited) {
        if (depth >= depthLimit.get()) {
            return Optional.empty();
        }
        for (MethodDeclaration target : graph.targetsOf(call)) {
            if (target.getBody().isEmpty()) {
                continue;
            }
            ReturnSummary summary = summaryOf(target);
            if (summary.intrinsic().isPresent()) {
                return summary.intrinsic();
            }
            for (int index : summary.parameters()) {
                // Последний параметр varargs получает все оставшиеся аргументы
                boolean varArgs = index == target.getParameters().size() - 1 && target.getParameter(index).isVarArgs();
                List<Expression> arguments = varArgs && index < call.getArguments().size()
                        ? call.getArguments().subList(index, call.getArguments().size())
                        : index < call.getArguments().size() ? List.of(call.getArgument(index)) : List.of();
                Optional<Origin> passed = firstSource(arguments, depth + 1, visited);
                if (passed.isPresent()) {
                    return passed;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Сводка метода для текущего места назначения: от него зависит, какая очистка внутри метода действует.
     * Рекурсивный вызов, сводка которого еще считается, ничего не возвращает
     */
    private ReturnSummary summaryOf(MethodDeclaration method) {
        Sink target = sink.get();
        Map<Sink, ReturnSummary> known;
        synchronized (summaries) {
            known = summaries.computeIfAbsent(method, key -> new EnumMap<>(Sink.class));
            ReturnSummary ready = known.get(target);
            if (ready != null) {
                return ready;
            }
        }
        Set<MethodDeclaration> computing = summarizing.get();
        if (!computing.add(method)) {
            guardHits.get()[0]++;
            return ReturnSummary.EMPTY;
        }
        int hitsBefore = guardHits.get()[0];
        ReturnSummary summary;
        try {
            summary = computeSummary(method);
        } finally {
            computing.remove(method);
        }
        // Сводка, при подсчете которой рекурсия оборвала разбор, верна только для этого пути
        if (guardHits.get()[0] == hitsBefore) {
            synchronized (summaries) {
                known.put(target, summary);
            }
        }
        return summary;
    }

    // Сначала - нет ли источника в самом методе при чистых параметрах; затем для каждого параметра - доходит ли
    // он до return, когда меченым считается только он
    private ReturnSummary computeSummary(MethodDeclaration method) {
        List<Expression> returned = method.findAll(ReturnStmt.class).stream()
                .filter(statement -> !Nodes.isInNestedScope(statement, method))
                .flatMap(statement -> statement.getExpression().stream())
                .toList();
        if (returned.isEmpty()) {
            return ReturnSummary.EMPTY;
        }
        Optional<Origin> intrinsic = traceReturns(method, returned, NO_PARAMETER);
        if (intrinsic.isPresent() && !isParameterMarker(intrinsic.get())) {
            return new ReturnSummary(intrinsic, Set.of());
        }
        Set<Integer> parameters = new LinkedHashSet<>();
        for (int index = 0; index < method.getParameters().size(); index++) {
            if (traceReturns(method, returned, index).filter(this::isParameterMarker).isPresent()) {
                parameters.add(index);
            }
        }
        return new ReturnSummary(Optional.empty(), Set.copyOf(parameters));
    }

    private Optional<Origin> traceReturns(MethodDeclaration method, List<Expression> returned, int parameter) {
        Deque<Marked> stack = marked.get();
        stack.push(new Marked(method, parameter));
        try {
            for (Expression value : returned) {
                Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                Optional<Origin> origin = source(value, 0, visited);
                if (origin.isPresent()) {
                    return origin;
                }
            }
            return Optional.empty();
        } finally {
            stack.pop();
        }
    }

    private boolean isParameterMarker(Origin origin) {
        return origin.text().startsWith(PARAMETER_MARKER);
    }

    // encodeForHTML(name) защищает ответ, но не запрос к базе; encodeBase64(name) не защищает ничего
    private boolean protects(String sanitizer) {
        if (TaintLibrary.containsWord(REVERSIBLE_ENCODING_WORDS, sanitizer)) {
            return false;
        }
        Sink target = sink.get();
        if (TaintLibrary.containsWord(HTML_ESCAPE_WORDS, sanitizer)) {
            return target == Sink.ANY || target == Sink.HTML;
        }
        if (TaintLibrary.containsWord(SQL_ESCAPE_WORDS, sanitizer)) {
            return target == Sink.ANY || target == Sink.SQL;
        }
        return true;
    }

    private boolean isSanitizer(String method) {
        return TaintLibrary.containsWord(SANITIZER_WORDS, method);
    }

    // Paths, Optional, Map: имя с большой буквы, которое не объявлено переменной, - класс
    private boolean isTypeReference(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        return value.isNameExpr() && Character.isUpperCase(value.asNameExpr().getNameAsString().charAt(0))
                && LocalTypes.findDeclaration(value).isEmpty();
    }

    private boolean isRequestWrapper(Node node) {
        return node.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> type.getExtendedTypes().stream()
                        .anyMatch(parent -> parent.getNameAsString().contains(REQUEST_TYPE_WORD)))
                .isPresent();
    }

    // request.getCookies() у HttpServletRequest; переменная без известного типа - по имени: request, req
    private boolean isServletRequest(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (LocalTypes.typeOf(value).filter(SERVLET_REQUEST_TYPES::contains).isPresent()) {
            return true;
        }
        return value.isNameExpr() && REQUEST_VARIABLE.matcher(value.asNameExpr().getNameAsString()).matches();
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
        // Сначала - что записали в этот же объект выше в этом методе: это тот же экземпляр, а не любой того же типа
        Optional<Optional<Origin>> local = sourceOfLocalWrites(getter, ownerType.get(), names, depth, visited);
        if (local.isPresent()) {
            return local.get().map(origin -> new Origin(
                    origin.text() + " через " + ownerType.get() + "." + property.get(), origin.callable(), origin.kind()));
        }
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
     * order.setTitle("Заказ"); ... order.getTitle() в одном методе: читается то, что записали сюда, а не то, что
     * пишут в свойство title где-то еще в проекте. Объект, созданный здесь же (new Order(comment)) и никуда не
     * переданный, несет только аргументы своего конструктора.
     *
     * @return пусто, если по этому методу вывода нет: объект пришел извне или его могли заполнить в другом методе
     */
    private Optional<Optional<Origin>> sourceOfLocalWrites(
            MethodCallExpr getter, String ownerType, List<String> names, int depth, Set<Node> visited) {
        Optional<Expression> owner = getter.getScope().map(Nodes::unwrap).filter(Expression::isNameExpr);
        Optional<Node> declaration = owner.flatMap(LocalTypes::findDeclaration);
        Optional<Node> callable = Nodes.enclosingCallable(getter);
        if (declaration.isEmpty() || callable.isEmpty()
                || declaration.get() instanceof VariableDeclarator variable && isField(variable)) {
            return Optional.empty();
        }
        List<Expression> written = new ArrayList<>();
        boolean escapes = false;
        for (MethodCallExpr call : callable.get().findAll(MethodCallExpr.class)) {
            if (!isBefore(call, getter) || ConstantValues.isUnreachable(call, callable.get())) {
                continue;
            }
            boolean onOwner = call.getScope().filter(scope -> isVariable(scope, declaration.get())).isPresent();
            if (onOwner && call.getArguments().size() == 1
                    && names.contains(PropertyAccess.writtenProperty(call.getNameAsString()))) {
                written.add(call.getArgument(0));
            } else if (call.getArguments().stream().anyMatch(argument -> isVariable(argument, declaration.get()))) {
                // fill(order), BeanUtils.copyProperties(dto, order): объект заполняют в другом месте
                escapes = true;
            }
        }
        if (!written.isEmpty()) {
            return Optional.of(firstSource(written, depth, visited));
        }
        Optional<ObjectCreationExpr> creation = Optional.of(declaration.get())
                .filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast)
                .filter(variable -> !isReassigned(variable, callable.get()))
                .flatMap(VariableDeclarator::getInitializer)
                .map(Nodes::unwrap)
                .filter(Expression::isObjectCreationExpr)
                .map(Expression::asObjectCreationExpr);
        if (creation.isEmpty() || escapes) {
            return Optional.empty();
        }
        List<String> properties = constructorProperties(ownerType, creation.get().getArguments().size());
        for (int index = 0; index < properties.size(); index++) {
            if (names.contains(properties.get(index))) {
                return Optional.of(source(creation.get().getArgument(index), depth, visited));
            }
        }
        return Optional.of(Optional.empty());
    }

    private boolean isVariable(Expression expression, Node declaration) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr() && LocalTypes.findDeclaration(value).filter(found -> found == declaration).isPresent();
    }

    private boolean isBefore(Node node, Node usage) {
        return node.getEnd().flatMap(end -> usage.getBegin().map(begin -> begin.isAfter(end))).orElse(false);
    }

    private boolean isReassigned(VariableDeclarator variable, Node callable) {
        return callable.findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> isVariable(assignment.getTarget(), variable));
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
        Optional<Handlers.Handler> handler = handlerOf(method);
        Parameter declared = handler.map(found -> found.declared(parameter)).orElse(parameter);
        // @Pattern проверил значение до входа в метод; значение enum не может быть ничем, кроме своих констант
        if (Annotations.has(parameter, PATTERN_ANNOTATION) || Annotations.has(declared, PATTERN_ANNOTATION)
                || isEnum(parameter)) {
            return false;
        }
        // Объект, собранный из тела запроса, целиком состоит из данных клиента
        if (isBoundToRequest(parameter) || isBoundToRequest(declared)) {
            return true;
        }
        // @POST public Response create(UserRepresentation user): в JAX-RS параметр без аннотаций - тело запроса
        if (Annotations.hasAny(method, JAX_RS_BODY_METHODS) && parameter.getAnnotations().isEmpty()
                && declared.getAnnotations().isEmpty()) {
            return true;
        }
        // Строковый параметр обработчика без аннотаций Spring берет из параметров запроса по имени
        return parameter.getAnnotations().isEmpty() && declared.getAnnotations().isEmpty()
                && (handler.isPresent() || Annotations.hasAny(method, MAPPING_ANNOTATIONS))
                && STRING.equals(LocalTypes.typeName(parameter.getType()));
    }

    private boolean isBoundToRequest(Parameter parameter) {
        return Annotations.hasAny(parameter, REQUEST_ANNOTATIONS) || Annotations.hasAny(parameter, BODY_ANNOTATIONS);
    }

    private boolean isRequestBody(Expression scope) {
        return scope.isNameExpr() && LocalTypes.findDeclaration(scope)
                .filter(node -> node instanceof Parameter)
                .map(node -> (Parameter) node)
                .filter(parameter -> Annotations.hasAny(parameter, BODY_ANNOTATIONS)
                        || Annotations.hasAny(declaredIn(parameter), BODY_ANNOTATIONS))
                .isPresent();
    }

    // Адрес и аннотации параметров обработчика бывают объявлены в интерфейсе, который реализует контроллер:
    // Spring берет их оттуда, значит и данные запроса приходят в такие параметры
    private Optional<Handlers.Handler> handlerOf(MethodDeclaration method) {
        return handlers.computeIfAbsent(method, key -> Handlers.of(key, classes));
    }

    private Parameter declaredIn(Parameter parameter) {
        return parameter.getParentNode()
                .filter(parent -> parent instanceof MethodDeclaration)
                .flatMap(parent -> handlerOf((MethodDeclaration) parent))
                .map(handler -> handler.declared(parameter))
                .orElse(parameter);
    }

    private boolean isField(VariableDeclarator variable) {
        return variable.getParentNode().filter(parent -> parent instanceof FieldDeclaration).isPresent();
    }
}
