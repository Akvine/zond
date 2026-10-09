package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.SpringBeans;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class StatefulBeanAsSingletonRule extends AbstractRule implements ProjectRule {
    /**
     * Почему объект нельзя делить между потоками и чем его заменить
     *
     * @param inField объект создают и прямо в поле бина; false - такие объекты встречаются только бинами
     */
    private record Kind(String reason, String alternative, boolean inField) {
    }

    private static final Kind FORMAT = new Kind(
            "не потокобезопасен: при одновременных вызовах результаты портят друг друга",
            " Для дат есть неизменяемый DateTimeFormatter - его можно оставить общим.", true);
    private static final Kind CALENDAR = new Kind(
            "изменяем: вычисление в одном потоке меняет дату, которую видят остальные",
            " Неизменяемые LocalDate, LocalDateTime и Instant можно делить без опаски.", true);
    private static final Kind CRYPTO = new Kind(
            "хранит незаконченное вычисление: одновременные вызовы подмешивают данные друг другу",
            " Создавать его на месте дешево: getInstance() берет готовый алгоритм.", true);
    private static final Kind XML = new Kind(
            "не потокобезопасен: одновременный разбор двух документов дает ошибки и перепутанные данные",
            " Общей можно оставить фабрику (JAXBContext, Templates, заранее настроенный DocumentBuilderFactory).", true);
    private static final Kind BUILDER = new Kind(
            "изменяемая заготовка: настройка, сделанная одним потребителем, достается всем остальным",
            "", false);
    private static final Kind MESSAGE = new Kind(
            "изменяемая заготовка письма: получатель и текст, заданные одним вызовом, попадут в чужое письмо",
            " Для шаблона письма есть копирование: new SimpleMailMessage(template).", false);
    private static final Kind CONNECTION = new Kind(
            "рассчитан на один поток и одну операцию за раз",
            " Бином обычно делают источник - DataSource, ConsumerFactory, пул, - а не само соединение.", false);
    private static final Kind WORK_BUFFER = new Kind(
            "копит состояние между вызовами: потоки будут писать в него вперемешку",
            "", true);

    private static final Map<String, Kind> STATEFUL = Map.ofEntries(
            Map.entry("SimpleDateFormat", FORMAT), Map.entry("DateFormat", FORMAT), Map.entry("NumberFormat", FORMAT),
            Map.entry("DecimalFormat", FORMAT), Map.entry("MessageFormat", FORMAT), Map.entry("ChoiceFormat", FORMAT),
            Map.entry("Collator", FORMAT),
            Map.entry("Calendar", CALENDAR), Map.entry("GregorianCalendar", CALENDAR),
            Map.entry("MessageDigest", CRYPTO), Map.entry("Cipher", CRYPTO), Map.entry("Mac", CRYPTO),
            Map.entry("Signature", CRYPTO), Map.entry("KeyAgreement", CRYPTO),
            Map.entry("DocumentBuilder", XML), Map.entry("SAXParser", XML), Map.entry("Transformer", XML),
            Map.entry("XPath", XML), Map.entry("XPathExpression", XML), Map.entry("Marshaller", XML),
            Map.entry("Unmarshaller", XML), Map.entry("XMLReader", XML),
            Map.entry("WebClient.Builder", BUILDER), Map.entry("RestClient.Builder", BUILDER),
            Map.entry("Jackson2ObjectMapperBuilder", BUILDER), Map.entry("UriComponentsBuilder", BUILDER),
            Map.entry("MimeMessageHelper", MESSAGE), Map.entry("SimpleMailMessage", MESSAGE),
            Map.entry("MimeMessage", MESSAGE),
            Map.entry("Connection", CONNECTION), Map.entry("Statement", CONNECTION),
            Map.entry("PreparedStatement", CONNECTION), Map.entry("CallableStatement", CONNECTION),
            Map.entry("ResultSet", CONNECTION), Map.entry("KafkaConsumer", CONNECTION), Map.entry("Jedis", CONNECTION),
            Map.entry("StringBuilder", WORK_BUFFER), Map.entry("StringBuffer", WORK_BUFFER),
            Map.entry("StopWatch", WORK_BUFFER));

    // Эти имена носят и потокобезопасные классы других библиотек (соединение JMS, AMQP): нужен импорт java.sql
    private static final Set<String> JDBC_TYPES = Set.of("Connection", "Statement", "PreparedStatement", "CallableStatement", "ResultSet");
    private static final String JDBC_PACKAGE = "java.sql";

    // EntityManager, внедренный контейнером, - потокобезопасная обертка; созданный вручную - нет
    private static final String ENTITY_MANAGER = "EntityManager";
    private static final String CREATE_ENTITY_MANAGER = "createEntityManager";

    private static final String BEAN = "Bean";
    private static final Set<String> STARTUP_ANNOTATIONS = Set.of("PostConstruct", "Bean", "Autowired", "Inject");
    // Для этих типов в статическом поле уже есть отдельное правило
    private static final Set<String> CHECKED_WHEN_STATIC = Set.of("SimpleDateFormat", "Calendar", "GregorianCalendar");
    private static final String HOW_TO_GET = " Экземпляр prototype-бина получайте через ObjectProvider или метод с @Lookup,"
            + " а не через ApplicationContext.getBean(): так зависимость остается видна контейнеру.";

    @Override
    public String code() {
        return RuleCodes.STATEFUL_BEAN_AS_SINGLETON_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет объекты с изменяемым состоянием, которые через бин-одиночку становятся общими для всех потоков: @Bean без области prototype и поля бинов";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface() || TestClasses.isInside(type)) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    checkBeanMethod(sourceFile, method, classes, violations);
                }
                if (BeanScopes.isSingletonBean(type)) {
                    checkFields(sourceFile, type, classes, violations);
                }
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

    // @Bean без области: Spring создаст один экземпляр и раздаст его всем
    private void checkBeanMethod(SourceFile sourceFile, MethodDeclaration method, ProjectClasses classes, List<Violation> violations) {
        if (!Annotations.has(method, BEAN) || BeanScopes.isShortLived(method)) {
            return;
        }
        Optional<String> typeName = statefulName(method.getType(), sourceFile, classes)
                .or(() -> manualEntityManager(method));
        typeName.ifPresent(name -> {
            Kind kind = STATEFUL.getOrDefault(name, CONNECTION);
            violations.add(violation(sourceFile, method,
                    "Бин '" + method.getNameAsString() + "' типа " + name + " объявлен без @Scope(\"prototype\") и потому"
                            + " один на все приложение, а " + name + " " + kind.reason() + ". Объявите бин с"
                            + " @Scope(\"prototype\"): каждая точка внедрения получит свой экземпляр." + HOW_TO_GET
                            + kind.alternative()));
        });
    }

    // Объект создан прямо в поле бина-одиночки и используется в его методах
    private void checkFields(SourceFile sourceFile, ClassOrInterfaceDeclaration bean, ProjectClasses classes, List<Violation> violations) {
        for (FieldDeclaration field : bean.getFields()) {
            Optional<String> typeName = statefulName(field.getElementType(), sourceFile, classes)
                    .filter(name -> STATEFUL.get(name).inField())
                    .filter(name -> !(field.isStatic() && CHECKED_WHEN_STATIC.contains(name)));
            if (typeName.isEmpty() || SpringBeans.isInjectedField(field, bean)) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                if (!isUsedWithoutLock(variable, bean)) {
                    continue;
                }
                Kind kind = STATEFUL.get(typeName.get());
                violations.add(violation(sourceFile, variable,
                        "Поле '" + variable.getNameAsString() + "' типа " + typeName.get() + " в бине-одиночке '"
                                + bean.getNameAsString() + "': объект один на все потоки, а " + typeName.get() + " "
                                + kind.reason() + ". Создавайте его в методе, где он нужен." + kind.alternative()
                                + " Если настройка объекта сложна, вынесите ее в бин с @Scope(\"prototype\")."
                                + HOW_TO_GET));
            }
        }
    }

    // Имя типа, если он из списка и это не одноименный класс самого проекта
    private Optional<String> statefulName(Type type, SourceFile sourceFile, ProjectClasses classes) {
        if (!type.isClassOrInterfaceType()) {
            return Optional.empty();
        }
        ClassOrInterfaceType declared = type.asClassOrInterfaceType();
        String simple = declared.getNameAsString();
        // WebClient.Builder: вложенный тип узнается только вместе с внешним
        boolean nested = declared.getScope()
                .filter(scope -> Character.isUpperCase(scope.getNameAsString().charAt(0)))
                .isPresent();
        String name = nested ? declared.getScope().get().getNameAsString() + "." + simple : simple;
        if (!STATEFUL.containsKey(name) || classes.isDeclared(simple) && !nested) {
            return Optional.empty();
        }
        if (JDBC_TYPES.contains(name) && !imports(sourceFile, JDBC_PACKAGE)) {
            return Optional.empty();
        }
        // Тип разрешен и оказался своим классом с таким же именем - не наш случай
        boolean anotherType = !nested && Types.isKindOf(type, Set.of(simple)).filter(kindOf -> !kindOf).isPresent();
        return anotherType ? Optional.empty() : Optional.of(name);
    }

    private Optional<String> manualEntityManager(MethodDeclaration method) {
        boolean manual = ENTITY_MANAGER.equals(LocalTypes.typeName(method.getType()))
                && method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> CREATE_ENTITY_MANAGER.equals(call.getNameAsString()));
        return manual ? Optional.of(ENTITY_MANAGER) : Optional.empty();
    }

    private boolean imports(SourceFile sourceFile, String packageName) {
        return sourceFile.unit().getImports().stream()
                .anyMatch(declaration -> declaration.getNameAsString().startsWith(packageName));
    }

    // Хотя бы одно обращение из обычного метода вне synchronized: при запуске и под блокировкой объект не делится
    private boolean isUsedWithoutLock(VariableDeclarator field, ClassOrInterfaceDeclaration bean) {
        for (MethodDeclaration method : bean.getMethods()) {
            if (method.isSynchronized() || Annotations.hasAny(method, STARTUP_ANNOTATIONS)) {
                continue;
            }
            // format.parse(...) либо this.format.parse(...)
            List<Expression> usages = new ArrayList<>(method.findAll(NameExpr.class));
            method.findAll(FieldAccessExpr.class).stream()
                    .filter(access -> access.getScope().isThisExpr())
                    .forEach(usages::add);
            for (Expression usage : usages) {
                boolean isField = LocalTypes.findDeclaration(usage).filter(field::equals).isPresent();
                if (isField && usage.findAncestor(SynchronizedStmt.class).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
