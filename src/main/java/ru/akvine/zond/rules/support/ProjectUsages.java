package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
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
import ru.akvine.zond.models.TestPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.stream.Stream;

/**
 * Что в проекте используется: до каких классов и методов выполнение может дойти от точек входа.
 * <p>
 * Точки входа - то, что вызывает не код проекта: классы и методы с аннотациями фреймворков, main, тесты,
 * геттеры и сеттеры, переопределенные методы. В библиотеке - проекте без main и {@code @SpringBootApplication} -
 * точки входа еще и открытые классы с их открытыми методами: ими пользуются проекты, которые ее подключают. От них использование распространяется по ссылкам и вызовам:
 * метод, который вызывается только из неиспользуемого метода, тоже не используется.
 * <p>
 * Ответ "используется" дается с запасом. Вызов, который не удалось сопоставить с конкретным методом,
 * засчитывается всем методам проекта с таким именем; имя, встретившееся в строке (рефлексия, SpEL),
 * тоже считается использованием.
 */
public final class ProjectUsages {
    private static final Pattern LINE_COMMENT = Pattern.compile("#.*");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final String TEST_DIRECTORY = "test";
    private static final String MAIN = "main";
    private static final String SOURCE_DIRECTORY = "src";
    private static final Path SERVICES_DIRECTORY = Path.of("src", "main", "resources", "META-INF", "services");
    // Шаблоны представлений вызывают методы по имени: ${it.hasScript()} в Jelly, th:text="${user.fullName()}"
    private static final List<Path> VIEW_DIRECTORIES = List.of(
            Path.of("src", "main", "resources"), Path.of("src", "main", "webapp"));
    private static final Pattern VIEW_FILE = Pattern.compile(
            "(?i).*\\.(jelly|groovy|jsp|jspx|tag|html|xhtml|ftl|ftlh|vm|peb|mustache|hbs)$");
    // Больше файлов не читаем: имена из шаблонов - лишь запас против ложных находок
    private static final int MAX_VIEW_FILES = 20_000;
    private static final Pattern CONFIGURATION_FILE = Pattern.compile(
            ".*\\.(xml|ya?ml|properties|factories|imports|json|conf)", Pattern.CASE_INSENSITIVE);
    private static final long MAX_CONFIGURATION_FILE_SIZE = 1_000_000;
    private static final Pattern BUILD_SCRIPT = Pattern.compile(".*\\.gradle(\\.kts)?");
    // ru.app.liquibase.DataLoader, com.acme.jobs.CleanupJob$Step: пакет строчными буквами и имя класса
    private static final Pattern QUALIFIED_CLASS = Pattern.compile(
            "\\b(?:[a-z_][a-z0-9_]*\\.){2,}(?:[A-Z][A-Za-z0-9_]*\\$)*([A-Z][A-Za-z0-9_]*)");
    // doCheckUrl, doFillGoalTypeItems, doConfigSubmit: веб-методы Stapler (Jenkins) вызываются по адресу запроса
    private static final Pattern STAPLER_WEB_METHOD = Pattern.compile("^do[A-Z]\\w*");
    private static final String STAPLER_PACKAGE = "org.kohsuke.stapler";
    private static final String SPRING_BOOT_APPLICATION = "SpringBootApplication";
    private static final String GET_BEAN = "getBean";

    // Аннотации, которые не означают, что кодом пользуется фреймворк
    private static final Set<String> NEUTRAL_ANNOTATIONS = Set.of(
            "Deprecated", "SuppressWarnings", "SafeVarargs", "FunctionalInterface", "Nullable", "NonNull", "NotNull",
            // Lombok только дописывает код
            "Data", "Getter", "Setter", "Value", "Builder", "ToString", "EqualsAndHashCode", "With",
            "AllArgsConstructor", "NoArgsConstructor", "RequiredArgsConstructor", "UtilityClass", "Accessors",
            "FieldDefaults", "Slf4j", "Log4j2", "Log", "CommonsLog");

    // Бин, который контейнер только создает: сам по себе @Component ничего не вызывает
    private static final Set<String> PLAIN_STEREOTYPES = Set.of("Component", "Service");
    // Аннотации класса и его членов, по которым Spring не вызывает код сам: внедрение, обертки над вызовом,
    // условия создания. Все остальные (@Scheduled, @EventListener, @PostConstruct, @Bean, слушатели очередей)
    // означают, что у бина есть работа и без ссылок на него
    private static final Set<String> PASSIVE_ANNOTATIONS = Set.of(
            "Override", "Autowired", "Inject", "Resource", "Qualifier", "Lazy", "Primary", "Order", "Scope",
            "Profile", "Transactional", "Validated", "Valid", "SneakyThrows", "Synchronized", "Cacheable",
            "CacheEvict", "CachePut", "Async", "Retryable", "Recover", "Timed", "PreAuthorize", "PostAuthorize",
            "Secured");

    // getName(), setName(...), isActive(): их вызывают по имени свойства Jackson, JPA, шаблоны и Spring
    private static final Pattern ACCESSOR = Pattern.compile("^(get|set|is)[A-Z].*");

    // Обработчик события JavaFX: onAction="#settingsClicked" в разметке FXML вызывает его по имени
    private static final String JAVAFX_PACKAGE = "javafx.";
    private static final Pattern UI_EVENT = Pattern.compile(".*Event$");

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
    // Слово из строки -> классы приложения, в коде которых стоит эта строка; у слова из шаблона или файла настроек
    // владелец пустой. Упоминание класса в его собственном сообщении ("OrderMapper: unknown mode") и в строке
    // теста - не использование в приложении
    private final Map<String, Set<String>> stringWordOwners = new HashMap<>();
    private final Set<CompilationUnit> testUnits = identitySet();
    // Модуль файла - путь до каталога src: activiti-core/activiti-engine, bitcoinj/core
    private final Map<CompilationUnit, String> moduleOf = new IdentityHashMap<>();
    private final Set<String> modulesWithEntry = new HashSet<>();
    private final Set<CompilationUnit> sampleUnits = identitySet();
    // Классы из META-INF/services и из файлов настроек: их создает ServiceLoader или фреймворк, а не код проекта
    private final Set<String> serviceClasses = new HashSet<>();
    // В проекте нет ни main, ни @SpringBootApplication: это библиотека
    private final boolean library;
    // Модули, в которых лежит свой @SpringBootApplication: в таком приложении все собирает контейнер, и до нужного
    // класса обязательно доходит ссылка из кода. У программы с обычным main классы могут подключаться и по имени -
    // расширения, протоколы, команды
    private final Set<String> springBootModules = new HashSet<>();
    // Бины где-то берут по имени или классу, которых в коде нет: getBean(name), getBean(job.getBeanName())
    private final boolean dynamicBeanLookup;

    // Что вызывает каждый метод. Ключ null - код вне методов: конструкторы, инициализаторы, значения полей
    private final Map<MethodDeclaration, Calls> callsByMethod = new IdentityHashMap<>();
    private final List<SiteOutsideMethod> sitesOutsideMethods = new ArrayList<>();
    private final Map<MethodDeclaration, Set<MethodDeclaration>> callers = new IdentityHashMap<>();

    private final Set<TypeDeclaration<?>> liveTypes = identitySet();
    private final Set<MethodDeclaration> liveMethods = identitySet();
    // То же, что liveTypes, но без тестов: до этих классов доходит сам код приложения
    private final Set<TypeDeclaration<?>> productionTypes = identitySet();
    // Класс, нужный только тестам, -> другие такие же классы, которые нужны только ему. Ключи - вершины: классы,
    // на которые из кода приложения не ссылается никто
    private final Map<TypeDeclaration<?>, Set<String>> testOnlyClusters = new IdentityHashMap<>();

    // Имена, по которым из используемого кода вызывают неизвестно какой метод, и имена всех вызовов оттуда
    private final Set<String> livePossibleNames = new HashSet<>();
    private final Set<String> liveCalledNames = new HashSet<>();

    // Обе проверки (методы и классы) идут подряд по одному списку файлов - считаем один раз
    private static final PerSources<ProjectUsages> CACHE = new PerSources<>();

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

    public static ProjectUsages of(List<SourceFile> sources) {
        return CACHE.get(sources, ProjectUsages::new);
    }

    private ProjectUsages(List<SourceFile> sources) {
        CallGraph graph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            if (isInTestDirectory(source)) {
                testUnits.add(source.unit());
            }
            if (TestPaths.isSample(source.path())) {
                sampleUnits.add(source.unit());
            }
            moduleOf.put(source.unit(), moduleOf(source.path()));
            for (Node node : source.findAll(Node.class)) {
                index(node);
            }
        }
        for (SourceFile source : sources) {
            for (Node node : source.findAll(Node.class)) {
                collectCalls(node, graph);
            }
        }
        new HashSet<>(moduleOf.values()).forEach(this::readServiceRegistrations);
        new HashSet<>(moduleOf.values()).forEach(this::readConfiguredClasses);
        new HashSet<>(moduleOf.values()).forEach(this::readViewWords);
        // Приложение из примеров (activiti-examples/DemoApplication) библиотеку приложением не делает
        types.stream()
                .filter(type -> !isInTests(type) && isApplication(type))
                .forEach(type -> type.findCompilationUnit().map(moduleOf::get).ifPresent(modulesWithEntry::add));
        // activiti-engine со служебным DbSchemaCreate.main - все равно библиотека: им пользуются другие модули
        modulesWithEntry.removeIf(this::isUsedByOtherModules);
        library = types.stream()
                .filter(type -> !isInTests(type) && !isInSamples(type))
                .noneMatch(this::isApplication);
        types.stream()
                .filter(type -> !isInTests(type) && !isInSamples(type))
                .filter(type -> Annotations.has(type, SPRING_BOOT_APPLICATION))
                .forEach(type -> type.findCompilationUnit().map(moduleOf::get).ifPresent(springBootModules::add));
        dynamicBeanLookup = sources.stream()
                .flatMap(source -> source.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> GET_BEAN.equals(call.getNameAsString()) && !call.getArguments().isEmpty()
                        && !call.getArgument(0).isClassExpr() && !call.getArgument(0).isStringLiteralExpr()
                        && !isInTests(call));
        markLiveTypes();
        markLiveMethods();
        markProductionTypes();
        groupTestOnlyTypes();
    }

    /**
     * @return true, если до класса не дойти от точек входа: на него нет ссылок либо ссылаются только
     * такие же неиспользуемые классы
     */
    public boolean isTypeDead(TypeDeclaration<?> type) {
        return !liveTypes.contains(type);
    }

    /**
     * @return true для открытого класса или метода модуля без своей точки входа (main, @SpringBootApplication):
     * bitcoinj/core, внутренний модуль большого приложения. Им могут пользоваться снаружи проекта, и о том,
     * что они не нужны, можно только подозревать
     */
    public boolean isModuleApi(Node declaration) {
        Optional<TypeDeclaration<?>> owner = declaration instanceof TypeDeclaration<?> type ? Optional.of(type)
                : declaration instanceof MethodDeclaration method ? ownerOf(method) : Optional.empty();
        boolean visible = !(declaration instanceof MethodDeclaration method)
                || method.isPublic() || method.isProtected();
        return owner.isPresent() && visible && isPublicApi(owner.get())
                && owner.get().findCompilationUnit()
                .map(moduleOf::get)
                .filter(module -> !modulesWithEntry.contains(module))
                .isPresent();
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
     * @return true, если класс приложения жив только за счет тестов: из кода самого приложения до него не дойти.
     * В библиотеке открытый класс - точка входа, и к нему это не относится
     */
    public boolean isUsedOnlyByTests(TypeDeclaration<?> type) {
        return liveTypes.contains(type) && !productionTypes.contains(type) && !isInTests(type)
                && !isInSamples(type)
                && type.findCompilationUnit().map(moduleOf::get).filter(springBootModules::contains).isPresent();
    }

    /**
     * Классы, нужные только тестам, обычно идут группой: фабрика, которую вызывает тест, и все, что она за собой
     * тянет. Сообщать стоит о вершине группы - классе, на который код приложения не ссылается вовсе
     *
     * @return остальные классы группы; пусто, если класс не вершина либо используется приложением
     */
    public Optional<Set<String>> testOnlyCluster(TypeDeclaration<?> type) {
        return Optional.ofNullable(testOnlyClusters.get(type));
    }

    /**
     * @return тестовые классы, которые ссылаются на класс
     */
    public Set<String> testUsersOf(TypeDeclaration<?> type) {
        Set<String> users = new LinkedHashSet<>();
        for (Node reference : referencesTo(type)) {
            if (isInTests(reference)) {
                innermostType(reference).ifPresent(user -> users.add(user.getNameAsString()));
            }
        }
        return users;
    }

    /**
     * Бин, который контейнер создает впустую: обычный {@code @Component} или {@code @Service} без предков, на
     * который в проекте нет ни одной ссылки и у которого нет ничего, что Spring вызвал бы сам. По типу его никто
     * не внедряет, по имени не получает, а свою работу он не начинает
     */
    public boolean isIdleBean(TypeDeclaration<?> type) {
        if (library || dynamicBeanLookup || !(type instanceof ClassOrInterfaceDeclaration bean)
                || bean.isInterface() || bean.isAbstract()
                || isInTests(type) || isInSamples(type) || !referencesTo(type).isEmpty()) {
            return false;
        }
        Set<String> annotations = Annotations.names(bean);
        boolean plain = annotations.stream().anyMatch(PLAIN_STEREOTYPES::contains)
                && annotations.stream().allMatch(name -> PLAIN_STEREOTYPES.contains(name) || isPassive(name))
                // @Service("orders"): бин с именем получают по имени, а имя могло прийти из настроек
                && bean.getAnnotations().stream().noneMatch(annotation -> !annotation.isMarkerAnnotationExpr()
                && PLAIN_STEREOTYPES.contains(annotation.getName().getIdentifier()));
        if (!plain || !bean.getExtendedTypes().isEmpty() || !bean.getImplementedTypes().isEmpty()) {
            return false;
        }
        String beanName = Character.toLowerCase(bean.getNameAsString().charAt(0)) + bean.getNameAsString().substring(1);
        boolean passiveMembers = bean.getMembers().stream()
                .allMatch(member -> member.getAnnotations().stream()
                        .allMatch(annotation -> isPassive(annotation.getName().getIdentifier())));
        // Конструктор с вызовами мог сам куда-то записать бин: registry.register(this)
        boolean quietCreation = bean.getConstructors().stream()
                .allMatch(constructor -> constructor.findAll(MethodCallExpr.class).isEmpty())
                && bean.getMembers().stream().noneMatch(member -> member instanceof InitializerDeclaration);
        return passiveMembers && quietCreation && !stringWords.contains(beanName)
                && bean.getMethods().stream().anyMatch(method -> !method.isPrivate());
    }

    private boolean isPassive(String annotation) {
        return NEUTRAL_ANNOTATIONS.contains(annotation) || PASSIVE_ANNOTATIONS.contains(annotation);
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
            collectWords(literal.getValue(), literal);
        } else if (node instanceof TextBlockLiteralExpr literal) {
            collectWords(literal.getValue(), literal);
        }
    }

    private void collectWords(String text, Node literal) {
        Matcher matcher = IDENTIFIER.matcher(text);
        String owner = null;
        while (matcher.find()) {
            String word = matcher.group();
            stringWords.add(word);
            // Имя класса начинается с заглавной буквы: остальные слова владельца не запоминают
            if (Character.isUpperCase(word.charAt(0))) {
                Set<String> owners = stringWordOwners.computeIfAbsent(word, key -> new HashSet<>());
                // Строка из теста коду приложения ничего не доказывает: ее владелец не запоминается
                if (!isInTests(literal)) {
                    if (owner == null) {
                        owner = outermostTypeName(literal);
                    }
                    owners.add(owner);
                }
            }
        }
    }

    private String outermostTypeName(Node node) {
        String name = "";
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                name = type.getNameAsString();
            }
            current = current.getParentNode().orElse(null);
        }
        return name;
    }

    // Имя класса встретилось в строке не только в его собственном коде: рефлексия, SpEL, файл настроек
    private boolean isNamedElsewhere(TypeDeclaration<?> type) {
        String name = type.getNameAsString();
        if (!stringWords.contains(name)) {
            return false;
        }
        String self = outermostTypeName(type.getName());
        return stringWordOwners.getOrDefault(name, Set.of()).stream().anyMatch(owner -> !owner.equals(self));
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

    // Тот же обход, что в markLiveTypes, но тесты точками входа не считаются и ссылки из них не засчитываются
    private void markProductionTypes() {
        for (TypeDeclaration<?> type : types) {
            if (!isInTests(type) && (isEntryType(type) || isNamedElsewhere(type))) {
                productionTypes.add(type);
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (TypeDeclaration<?> type : types) {
                if (!productionTypes.contains(type) && !isInTests(type)
                        && referencesTo(type).stream().anyMatch(this::isInProductionCode)) {
                    productionTypes.add(type);
                    changed = true;
                }
            }
        }
    }

    // Вершины групп и то, что нужно только им. Группа, замкнутая сама на себя (A использует B, B использует A),
    // вершины не имеет: ею становится первый ее класс
    private void groupTestOnlyTypes() {
        List<TypeDeclaration<?>> testOnly = types.stream()
                .filter(this::isUsedOnlyByTests)
                .filter(type -> type.findAncestor(TypeDeclaration.class).filter(this::isUsedOnlyByTests).isEmpty())
                .toList();
        // Кто из таких классов на кого ссылается
        Map<TypeDeclaration<?>, Set<TypeDeclaration<?>>> uses = new IdentityHashMap<>();
        Set<TypeDeclaration<?>> used = identitySet();
        for (TypeDeclaration<?> type : testOnly) {
            for (Node reference : referencesTo(type)) {
                Optional<TypeDeclaration<?>> user = isInTests(reference) ? Optional.empty() : outermostType(reference);
                if (user.isPresent() && user.get() != type && testOnly.contains(user.get())) {
                    uses.computeIfAbsent(user.get(), key -> identitySet()).add(type);
                    used.add(type);
                }
            }
        }
        Set<TypeDeclaration<?>> covered = identitySet();
        List<TypeDeclaration<?>> roots = new ArrayList<>();
        testOnly.stream().filter(type -> !used.contains(type)).forEach(roots::add);
        roots.addAll(testOnly);
        for (TypeDeclaration<?> root : roots) {
            if (covered.contains(root)) {
                continue;
            }
            Set<String> pulled = new LinkedHashSet<>();
            Deque<TypeDeclaration<?>> queue = new ArrayDeque<>(List.of(root));
            covered.add(root);
            while (!queue.isEmpty()) {
                for (TypeDeclaration<?> next : uses.getOrDefault(queue.poll(), Set.of())) {
                    if (covered.add(next)) {
                        pulled.add(next.getNameAsString());
                        queue.add(next);
                    }
                }
            }
            testOnlyClusters.put(root, pulled);
        }
    }

    private Optional<TypeDeclaration<?>> outermostType(Node node) {
        TypeDeclaration<?> outermost = null;
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                outermost = type;
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.ofNullable(outermost);
    }

    private boolean isInProductionCode(Node node) {
        if (isInTests(node)) {
            return false;
        }
        Optional<TypeDeclaration<?>> type = innermostType(node);
        if (type.isPresent()) {
            return productionTypes.contains(type.get());
        }
        return node.findCompilationUnit()
                .filter(unit -> unit.getTypes().stream().anyMatch(productionTypes::contains))
                .isPresent();
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
                || serviceClasses.contains(type.getNameAsString())
                || isInTests(type)
                || isFrameworkEntry(type)
                || type.getMethods().stream().anyMatch(this::isMain)
                || library && isPublicApi(type);
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
                || isFxmlHandler(method)
                || isStaplerWebMethod(method)
                || stringWords.contains(name)
                || library && (method.isPublic() || method.isProtected()) && isPublicApi(owner)
                || mayOverride(method, owner, new HashSet<>());
    }

    // settingsClicked(ActionEvent event) в контроллере JavaFX: разметку FXML проверка не читает, а по коду такой
    // метод не вызывает никто
    private boolean isFxmlHandler(MethodDeclaration method) {
        return method.getParameters().size() == 1
                && UI_EVENT.matcher(LocalTypes.typeName(method.getParameter(0).getType())).matches()
                && method.findCompilationUnit().stream()
                .flatMap(unit -> unit.getImports().stream())
                .anyMatch(declaration -> declaration.getNameAsString().startsWith(JAVAFX_PACKAGE));
    }

    // Открытый класс, вложенный только в открытые: виден снаружи библиотеки
    private boolean isPublicApi(TypeDeclaration<?> type) {
        Node current = type;
        while (current != null) {
            if (current instanceof TypeDeclaration<?> declaration && !declaration.isPublic()) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return true;
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

    // META-INF/services/com.hazelcast.SerializerHook: com.hazelcast.jet.avro.impl.AvroSerializerHooks$GenericContainerHook
    private void readServiceRegistrations(String module) {
        if (module.isEmpty()) {
            return;
        }
        Path services = Path.of(module).resolve(SERVICES_DIRECTORY);
        if (!Files.isDirectory(services)) {
            return;
        }
        try (Stream<Path> files = Files.list(services)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String name = LINE_COMMENT.matcher(line).replaceAll("").trim();
                    if (!name.isEmpty()) {
                        serviceClasses.add(name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1));
                    }
                }
            }
        } catch (IOException | UncheckedIOException exception) {
            // Файл не прочитался - о классах из него судим по коду, как раньше
        }
    }

    /**
     * <customChange class="ru.app.liquibase.DataLoader"/> в миграции Liquibase, <bean class="..."> в XML Spring,
     * аппендер в logback.xml, задание в quartz.properties, spring.factories: класс назван полным именем в файле
     * настроек - создает его фреймворк, и ссылок из кода на него может не быть вовсе
     */
    private void readBuildScripts(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> BUILD_SCRIPT.matcher(path.getFileName().toString()).matches())
                    .forEach(this::readClassNames);
        } catch (IOException | UncheckedIOException exception) {
            // Скрипт сборки не прочитался - о классах судим по коду
        }
    }

    private void readConfiguredClasses(String module) {
        if (module.isEmpty()) {
            return;
        }
        // build.gradle.kts подключает задачи и плагины из buildSrc по полному имени класса: скрипт лежит
        // в корне модуля или проекта, а не в src
        readBuildScripts(Path.of(module));
        readBuildScripts(Path.of(module).toAbsolutePath().getParent());
        Path sourceRoot = Path.of(module).resolve(SOURCE_DIRECTORY);
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> CONFIGURATION_FILE.matcher(path.getFileName().toString()).matches())
                    .limit(MAX_VIEW_FILES)
                    .forEach(this::readClassNames);
        } catch (IOException | UncheckedIOException exception) {
            // Настройки не прочитались - о классах судим по коду, как раньше
        }
    }

    private void readClassNames(Path file) {
        try {
            if (Files.size(file) > MAX_CONFIGURATION_FILE_SIZE) {
                return;
            }
            try (Stream<String> lines = Files.lines(file, StandardCharsets.ISO_8859_1)) {
                lines.forEach(this::readClassNames);
            }
        } catch (IOException | UncheckedIOException exception) {
            // Один файл не прочитался - остальные все равно пригодятся
        }
    }

    private void readClassNames(String line) {
        Matcher matcher = QUALIFIED_CLASS.matcher(line);
        while (matcher.find()) {
            serviceClasses.add(matcher.group(1));
        }
    }

    // Имена из шаблонов модуля считаются возможными вызовами - так же, как имена в строках кода
    private void readViewWords(String module) {
        if (module.isEmpty()) {
            return;
        }
        for (Path directory : VIEW_DIRECTORIES) {
            Path views = Path.of(module).resolve(directory);
            if (!Files.isDirectory(views)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(views)) {
                files.filter(path -> VIEW_FILE.matcher(path.getFileName().toString()).matches())
                        .limit(MAX_VIEW_FILES)
                        .forEach(this::readWords);
            } catch (IOException | UncheckedIOException exception) {
                // Шаблоны не прочитались - о методах судим по коду, как раньше
            }
        }
    }

    private void readWords(Path file) {
        try {
            Matcher matcher = IDENTIFIER.matcher(Files.readString(file, StandardCharsets.ISO_8859_1));
            while (matcher.find()) {
                String word = matcher.group();
                stringWords.add(word);
                // Слово из шаблона или файла настроек: классу оно заведомо не принадлежит
                if (Character.isUpperCase(word.charAt(0))) {
                    stringWordOwners.computeIfAbsent(word, key -> new HashSet<>()).add("");
                }
            }
        } catch (IOException exception) {
            // Один шаблон не прочитался - остальные все равно пригодятся
        }
    }

    // public HttpResponse doConfigSubmit(StaplerRequest req) в файле, где используется Stapler
    private boolean isStaplerWebMethod(MethodDeclaration method) {
        return STAPLER_WEB_METHOD.matcher(method.getNameAsString()).matches()
                && method.findCompilationUnit().stream()
                .flatMap(unit -> unit.getImports().stream())
                .anyMatch(declaration -> declaration.getNameAsString().startsWith(STAPLER_PACKAGE));
    }

    private boolean isUsedByOtherModules(String module) {
        return types.stream()
                .filter(type -> type.findCompilationUnit().map(moduleOf::get).filter(module::equals).isPresent())
                .flatMap(type -> identifiers.getOrDefault(type.getNameAsString(), List.of()).stream())
                .filter(usage -> !isInTests(usage))
                .anyMatch(usage -> usage.findCompilationUnit().map(moduleOf::get)
                        .filter(other -> !other.equals(module)).isPresent());
    }

    private boolean isApplication(TypeDeclaration<?> type) {
        return Annotations.has(type, SPRING_BOOT_APPLICATION) || type.getMethods().stream().anyMatch(this::isMain);
    }

    private boolean isInSamples(Node node) {
        return node.findCompilationUnit().filter(sampleUnits::contains).isPresent();
    }

    // /projects/bitcoinj/core/src/main/java/... -> /projects/bitcoinj/core
    private String moduleOf(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (current.getFileName() != null && SOURCE_DIRECTORY.equals(current.getFileName().toString())) {
                return current.getParent() == null ? "" : current.getParent().toString();
            }
            current = current.getParent();
        }
        return "";
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
                .filter(usage -> usage != type.getName() && !Nodes.isInside(usage, type))
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
