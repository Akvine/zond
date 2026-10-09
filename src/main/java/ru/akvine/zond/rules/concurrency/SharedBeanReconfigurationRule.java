package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.BeanScopes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.SpringBeans;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SharedBeanReconfigurationRule extends AbstractRule implements ProjectRule {
    private static final Pattern SETTER = Pattern.compile("set[A-Z].*");
    private static final Set<String> COLLECTION_CHANGES = Set.of(
            "add", "addAll", "addFirst", "addLast", "remove", "removeIf", "removeAll", "clear", "set", "replaceAll",
            "sort", "put", "putAll");

    /**
     * Вид общего объекта: чем он настраивается и что посоветовать вместо перенастройки
     *
     * @param changes     методы настройки помимо сеттеров
     * @param liveGetters геттеры, которые отдают саму внутреннюю коллекцию или настройки, а не копию
     * @param own         как получить отдельный экземпляр со своей настройкой
     */
    private record Kind(Set<String> changes, Set<String> liveGetters, String own) {
    }

    private static final Kind MAPPER = new Kind(
            Set.of("configure", "enable", "disable", "registerModule", "registerModules", "findAndRegisterModules",
                    "addMixIn", "activateDefaultTyping", "enableDefaultTyping", "deactivateDefaultTyping",
                    "registerSubtypes", "addHandler", "clearProblemHandlers"),
            Set.of("getFactory"),
            "копию через copy() с нужной настройкой либо неизменяемые reader() и writer()");
    private static final Kind REST_TEMPLATE = new Kind(
            Set.of(),
            Set.of("getInterceptors", "getMessageConverters", "getClientHttpRequestInitializers"),
            "свой RestTemplate через RestTemplateBuilder, а заголовки одного запроса передавайте через HttpEntity");
    private static final Kind JDBC_TEMPLATE = new Kind(
            Set.of(), Set.of("getJdbcTemplate"), "свой JdbcTemplate на том же DataSource");
    private static final Kind TRANSACTION_TEMPLATE = new Kind(
            Set.of(), Set.of(), "new TransactionTemplate(transactionManager) на месте - он легкий");
    private static final Kind MESSAGING = new Kind(
            Set.of(), Set.of(),
            "отдельный шаблон с @Qualifier, а адресата одного сообщения передавайте аргументом метода отправки");
    private static final Kind MODEL_MAPPER = new Kind(
            Set.of("addMappings", "addConverter", "createTypeMap", "registerModule"),
            Set.of("getConfiguration"),
            "отдельный настроенный бин с @Qualifier");
    private static final Kind MAIL_SENDER = new Kind(Set.of(), Set.of("getJavaMailProperties"), "отдельный JavaMailSender");

    private static final Map<String, Kind> SHARED_TYPES = Map.ofEntries(
            Map.entry("ObjectMapper", MAPPER), Map.entry("JsonMapper", MAPPER), Map.entry("XmlMapper", MAPPER),
            Map.entry("YAMLMapper", MAPPER), Map.entry("CsvMapper", MAPPER),
            Map.entry("RestTemplate", REST_TEMPLATE),
            Map.entry("JdbcTemplate", JDBC_TEMPLATE), Map.entry("NamedParameterJdbcTemplate", JDBC_TEMPLATE),
            Map.entry("TransactionTemplate", TRANSACTION_TEMPLATE),
            Map.entry("RabbitTemplate", MESSAGING), Map.entry("JmsTemplate", MESSAGING),
            Map.entry("KafkaTemplate", MESSAGING), Map.entry("RedisTemplate", MESSAGING),
            Map.entry("StringRedisTemplate", MESSAGING),
            Map.entry("ModelMapper", MODEL_MAPPER),
            Map.entry("JavaMailSenderImpl", MAIL_SENDER));

    // Классы настройки: менять общие бины - их прямое назначение, и делают они это при запуске
    private static final Set<String> CONFIGURATION_ANNOTATIONS =
            Set.of("Configuration", "TestConfiguration", "AutoConfiguration", "ConfigurationProperties");
    private static final List<String> CONFIGURATION_SUFFIXES =
            List.of("Configurer", "Customizer", "PostProcessor", "Initializer", "ConfigurerAdapter");

    private static final String BEAN = "Bean";
    private static final String QUALIFIER = "Qualifier";
    private static final int SHOWN_CONSUMERS = 3;
    private static final String NOT_VIA_CONTEXT = "а не через ApplicationContext.getBean()";

    /**
     * Что известно о бинах проекта
     *
     * @param consumers  тип бина -> классы, в которые он внедрен
     * @param prototypes типы, все объявления которых имеют область prototype: у каждого потребителя свой экземпляр
     */
    private record Beans(Map<String, Set<String>> consumers, Set<String> prototypes) {
    }

    @Override
    public String code() {
        return RuleCodes.SHARED_BEAN_RECONFIGURATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет перенастройку общего объекта: ObjectMapper, RestTemplate, JdbcTemplate и подобные меняют во время работы либо в одном из классов, которым внедрен общий бин";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Beans beans = collectBeans(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface() || TestClasses.isInside(type) || isConfiguration(type)) {
                    continue;
                }
                Map<VariableDeclarator, String> shared = sharedFields(type);
                if (shared.isEmpty()) {
                    continue;
                }
                Set<MethodDeclaration> startup = CodeContexts.startupMethods(type);
                for (MethodDeclaration method : type.getMethods()) {
                    for (Change change : changes(method, shared)) {
                        String where = "в методе '" + method.getNameAsString() + "'";
                        Optional<String> message = startup.contains(method)
                                ? describeForeign(change, type, shared, beans, where)
                                : describeRuntime(change, method, shared, where);
                        message.ifPresent(text -> violations.add(violation(sourceFile, change.call(), text)));
                    }
                }
                type.getConstructors().forEach(constructor -> changes(constructor, shared).forEach(change ->
                        describeForeign(change, type, shared, beans, "в конструкторе")
                                .ifPresent(text -> violations.add(violation(sourceFile, change.call(), text)))));
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
        return ErrorType.CONCURRENCY;
    }

    /**
     * Вызов, который меняет настройку общего объекта
     *
     * @param grows вызов добавляет элемент во внутренний список объекта
     */
    private record Change(MethodCallExpr call, VariableDeclarator field, boolean grows) {
    }

    private Beans collectBeans(List<SourceFile> sourceFiles) {
        Map<String, Set<String>> consumers = new HashMap<>();
        Set<String> prototypes = new HashSet<>();
        Set<String> singletons = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (SpringBeans.isBean(type) && !TestClasses.isInside(type)) {
                    SpringBeans.findDependencies(type).stream()
                            .filter(dependency -> SHARED_TYPES.containsKey(dependency.type()))
                            .forEach(dependency -> consumers.computeIfAbsent(dependency.type(), name -> new LinkedHashSet<>())
                                    .add(type.getNameAsString()));
                }
                for (MethodDeclaration method : type.getMethods()) {
                    if (Annotations.has(method, BEAN)) {
                        (BeanScopes.isShortLived(method) ? prototypes : singletons).add(LocalTypes.typeName(method.getType()));
                    }
                }
            }
        }
        prototypes.removeAll(singletons);
        return new Beans(consumers, prototypes);
    }

    private boolean isConfiguration(ClassOrInterfaceDeclaration type) {
        return Annotations.hasAny(type, CONFIGURATION_ANNOTATIONS) || type.getImplementedTypes().stream()
                .anyMatch(implemented -> CONFIGURATION_SUFFIXES.stream().anyMatch(implemented.getNameAsString()::endsWith));
    }

    // Поле бина-одиночки либо статическое поле: объект один на все потоки
    private Map<VariableDeclarator, String> sharedFields(ClassOrInterfaceDeclaration type) {
        Map<VariableDeclarator, String> fields = new HashMap<>();
        boolean singleton = BeanScopes.isSingletonBean(type);
        for (FieldDeclaration field : type.getFields()) {
            String typeName = LocalTypes.typeName(field.getElementType());
            if (SHARED_TYPES.containsKey(typeName) && (singleton || field.isStatic())) {
                field.getVariables().forEach(variable -> fields.put(variable, typeName));
            }
        }
        return fields;
    }

    private List<Change> changes(Node callable, Map<VariableDeclarator, String> shared) {
        List<Change> changes = new ArrayList<>();
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty()) {
                continue;
            }
            Expression scope = Nodes.unwrap(call.getScope().get());
            String name = call.getNameAsString();

            // restTemplate.setErrorHandler(...), objectMapper.configure(...)
            Optional<VariableDeclarator> direct = fieldOf(scope, shared);
            if (direct.isPresent()) {
                Kind kind = SHARED_TYPES.get(shared.get(direct.get()));
                if (SETTER.matcher(name).matches() || kind.changes().contains(name)) {
                    changes.add(new Change(call, direct.get(), false));
                }
                continue;
            }

            // restTemplate.getInterceptors().add(...), modelMapper.getConfiguration().setAmbiguityIgnored(true)
            if (!scope.isMethodCallExpr() || scope.asMethodCallExpr().getScope().isEmpty()) {
                continue;
            }
            MethodCallExpr getter = scope.asMethodCallExpr();
            Optional<VariableDeclarator> owner = fieldOf(Nodes.unwrap(getter.getScope().get()), shared);
            if (owner.isEmpty()) {
                continue;
            }
            Kind kind = SHARED_TYPES.get(shared.get(owner.get()));
            boolean grows = COLLECTION_CHANGES.contains(name);
            boolean changesState = grows || SETTER.matcher(name).matches() || MAPPER.changes().contains(name);
            if (kind.liveGetters().contains(getter.getNameAsString()) && changesState) {
                changes.add(new Change(call, owner.get(), grows));
            }
        }
        return changes;
    }

    // Перенастройка во время работы: меняется объект, которым в этот момент пользуются другие потоки
    private Optional<String> describeRuntime(
            Change change, MethodDeclaration method, Map<VariableDeclarator, String> shared, String where) {
        if (isOwnLazyCreation(change.field(), method)) {
            return Optional.empty();
        }
        String typeName = shared.get(change.field());
        return Optional.of(subject(change, typeName, where) + ": он один на все приложение, поэтому изменение достается"
                + " всем, кто им пользуется, и делается без синхронизации, пока с объектом работают другие потоки"
                + (change.grows() ? "; к тому же при каждом вызове в список добавляется еще один элемент" : "")
                + ". Настраивайте объект один раз там, где он создается. Если этому месту нужна своя настройка -"
                + " заведите отдельный экземпляр: " + SHARED_TYPES.get(typeName).own() + ". Годится и бин с"
                + " @Scope(\"prototype\"), если брать его через ObjectProvider, " + NOT_VIA_CONTEXT);
    }

    // Настройка при запуске, но чужого бина: тот же экземпляр внедрен в другие классы и изменится и для них
    private Optional<String> describeForeign(
            Change change, ClassOrInterfaceDeclaration type, Map<VariableDeclarator, String> shared, Beans beans,
            String where) {
        String typeName = shared.get(change.field());
        FieldDeclaration field = (FieldDeclaration) change.field().getParentNode().orElseThrow();
        // Свой объект, именованный бин и бин с областью prototype ни с кем не делятся
        boolean dedicated = !SpringBeans.isInjectedField(field, type) || hasQualifier(field, type)
                || beans.prototypes().contains(typeName);
        List<String> others = beans.consumers().getOrDefault(typeName, Set.of()).stream()
                .filter(consumer -> !consumer.equals(type.getNameAsString()))
                .toList();
        if (dedicated || others.isEmpty()) {
            return Optional.empty();
        }
        String shown = String.join(", ", others.subList(0, Math.min(SHOWN_CONSUMERS, others.size())))
                + (others.size() > SHOWN_CONSUMERS ? " и другие" : "");
        return Optional.of(subject(change, typeName, where) + ", а тот же бин внедрен еще в " + shown + ": настройка,"
                + " сделанная здесь, меняет его поведение и для них, причем зависит от порядка создания бинов."
                + " Если настройка нужна только этому классу - объявите бин с @Scope(\"prototype\"): тогда каждая"
                + " точка внедрения получит свой экземпляр, и настраивать его можно свободно (когда новый экземпляр"
                + " нужен на каждый вызов - берите его через ObjectProvider, " + NOT_VIA_CONTEXT + "). Либо заведите"
                + " отдельный бин с @Qualifier. Общую для всех настройку переносите туда, где бин объявлен");
    }

    private String subject(Change change, String typeName, String where) {
        MethodCallExpr call = change.call();
        return "'" + call.getScope().get() + "." + call.getNameAsString() + "(...)' " + where + " перенастраивает общий"
                + " объект '" + change.field().getNameAsString() + "' (" + typeName + ")";
    }

    // @Qualifier на поле либо на параметре конструктора того же типа: внедряется отдельный именованный бин
    private boolean hasQualifier(FieldDeclaration field, ClassOrInterfaceDeclaration type) {
        String typeName = LocalTypes.typeName(field.getElementType());
        return Annotations.has(field, QUALIFIER) || type.getConstructors().stream()
                .flatMap(constructor -> constructor.getParameters().stream())
                .anyMatch(parameter -> typeName.equals(LocalTypes.typeName(parameter.getType()))
                        && Annotations.has(parameter, QUALIFIER));
    }

    // x либо this.x, где x - общее поле, а не локальная переменная с тем же именем
    private Optional<VariableDeclarator> fieldOf(Expression expression, Map<VariableDeclarator, String> shared) {
        Optional<Node> declaration = LocalTypes.findDeclaration(expression);
        if (declaration.filter(found -> found instanceof VariableDeclarator && shared.containsKey(found)).isPresent()) {
            return declaration.map(found -> (VariableDeclarator) found);
        }
        // Параметр конструктора - тот же бин, что потом лежит в поле этого типа
        Optional<String> injected = declaration
                .filter(found -> found instanceof Parameter)
                .filter(found -> found.getParentNode().filter(parent -> parent instanceof ConstructorDeclaration).isPresent())
                .map(found -> LocalTypes.typeName(((Parameter) found).getType()));
        List<VariableDeclarator> sameType = shared.entrySet().stream()
                .filter(entry -> injected.filter(entry.getValue()::equals).isPresent())
                .map(Map.Entry::getKey)
                .toList();
        return sameType.size() == 1 ? Optional.of(sameType.get(0)) : Optional.empty();
    }

    // if (mapper == null) { mapper = new ObjectMapper(); mapper.configure(...); } - объект создается тут же
    private boolean isOwnLazyCreation(VariableDeclarator field, MethodDeclaration method) {
        return method.findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> LocalTypes.findDeclaration(assignment.getTarget()).filter(field::equals).isPresent());
    }
}
