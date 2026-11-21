package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ConfigKeys;
import ru.akvine.zond.rules.support.AnnotationValues;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Свойство-переключатель со значением, для которого в проекте нет бина. Бины создаются по
 * {@code @ConditionalOnProperty(name = "bot.type", havingValue = "...")} для нескольких значений, а в настройках
 * стоит еще одно, которого среди них нет: приложение запускается без этой части и об ошибке не сообщает.
 * <p>
 * Судим, только когда значение свойства никто, кроме этих условий, не читает и запасного бина того же типа нет:
 * иначе у "лишнего" значения может быть свой смысл - его разбирает код либо оно означает вариант по умолчанию
 */
@Component
public class PropertyValueWithoutConditionRule extends AbstractContextRule {
    private static final String CONDITIONAL = "ConditionalOnProperty";
    private static final String CONFIGURATION_PROPERTIES = "ConfigurationProperties";
    private static final String BEAN = "Bean";
    private static final Set<String> STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration");
    private static final Set<String> NAME_MEMBERS = Set.of("name", "value");
    private static final Set<String> PREFIX_MEMBER = Set.of("prefix");
    private static final Set<String> PROPERTIES_PREFIX = Set.of("prefix", "value");
    private static final Set<String> HAVING_MEMBER = Set.of("havingValue");
    private static final String UNKNOWN_PREFIX = "<";
    // Значения, которыми часть приложения выключают намеренно
    private static final Set<String> OFF_VALUES = Set.of(
            "", "none", "off", "disabled", "disable", "false", "no", "noop", "nop", "null", "default");
    // ${BOT_TYPE:webhook}: без переменной окружения действует значение после двоеточия
    private static final Pattern WITH_DEFAULT = Pattern.compile("^\\$\\{[^:}]+:([^}]*)}$");
    private static final String PLACEHOLDER = "${";
    // Имена свойств внутри строки: "${bot.type}", "'${bot.type}' == 'webhook'", "bot.type"
    private static final Pattern PROPERTY_NAME = Pattern.compile("[\\w.\\-]+");
    // Переключатель - свойство, по значениям которого выбирают между несколькими бинами
    private static final int MIN_VALUES = 2;
    private static final int MAX_NAMED = 3;

    /**
     * Свойство, по значениям которого создают бины
     *
     * @param byValue значение -> бины, которые при нем создаются
     * @param types   под какими типами эти бины объявлены
     * @param owners  методы и классы, на которых стоит условие
     */
    private record Switch(Map<String, Set<String>> byValue, Set<String> types, Set<Node> owners) {

        private Switch() {
            this(new TreeMap<>(String.CASE_INSENSITIVE_ORDER), new HashSet<>(),
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }
    }

    @Override
    public String code() {
        return RuleCodes.PROPERTY_VALUE_WITHOUT_CONDITION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет настройки с кодом и ищет значение свойства, под которое нет ни одного"
                + " @ConditionalOnProperty, хотя под другие значения бины есть";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Map<String, Switch> switches = new HashMap<>();
        // Свойства с условием без havingValue: такому условию подходит любое значение, кроме false
        Set<String> anyValue = new HashSet<>();
        collect(context.sources(), switches, anyValue);
        switches.keySet().removeIf(key -> anyValue.contains(key) || switches.get(key).byValue().size() < MIN_VALUES);
        if (switches.isEmpty()) {
            return List.of();
        }
        // Свойство читает и сам код, либо для его бинов есть запасной вариант: чужое значение там не ошибка
        Set<String> readByCode = readByCode(context.sources(), switches.keySet());
        switches.entrySet().removeIf(entry -> readByCode.contains(entry.getKey())
                || hasFallback(entry.getValue(), context.sources()));

        List<Violation> violations = new ArrayList<>();
        for (ConfigFile configFile : context.configFiles()) {
            if (configFile.isNonProduction()) {
                continue;
            }
            for (ConfigProperty property : configFile.properties()) {
                Switch found = switches.get(ConfigKeys.normalize(property.key()));
                Optional<String> value = effective(property.value());
                if (found == null || value.isEmpty() || found.byValue().containsKey(value.get())
                        || OFF_VALUES.contains(value.get().toLowerCase(Locale.ROOT))
                        || configFile.isNonProduction(property)) {
                    continue;
                }
                Set<String> owners = new TreeSet<>();
                found.byValue().values().forEach(owners::addAll);
                violations.add(violation(configFile.path(), property.line(),
                        property.key() + "=" + value.get() + ": бины проекта создаются только при значениях "
                                + String.join(", ", found.byValue().keySet()) + " ("
                                + String.join(", ", owners.stream().limit(MAX_NAMED).toList())
                                + (owners.size() > MAX_NAMED ? " и другие" : "") + "), а для '" + value.get()
                                + "' условия нет - ни один из них создан не будет, и приложение запустится без"
                                + " этой части, не сообщив об ошибке; исправьте значение либо добавьте бин под него"));
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
        return ErrorType.LOGICAL;
    }

    private Optional<String> effective(String raw) {
        String value = raw.trim();
        Matcher withDefault = WITH_DEFAULT.matcher(value);
        if (withDefault.matches()) {
            value = withDefault.group(1).trim();
        }
        return value.contains(PLACEHOLDER) ? Optional.empty() : Optional.of(value);
    }

    // Условия берутся и из тестов: бин под значение из тестовых настроек объявляют там же
    private void collect(List<SourceFile> sources, Map<String, Switch> switches, Set<String> anyValue) {
        ProjectClasses classes = ProjectClasses.of(sources);
        for (SourceFile source : sources) {
            for (AnnotationExpr annotation : source.findAll(AnnotationExpr.class)) {
                if (!CONDITIONAL.equals(annotation.getName().getIdentifier())) {
                    continue;
                }
                List<String> names = AnnotationValues.keys(annotation, NAME_MEMBERS, classes);
                List<String> prefix = AnnotationValues.keys(annotation, PREFIX_MEMBER, classes);
                List<String> having = AnnotationValues.keys(annotation, HAVING_MEMBER, classes);
                Optional<Node> owner = annotation.getParentNode();
                String ownerName = annotation.findAncestor(TypeDeclaration.class)
                        .map(type -> type.getNameAsString()).orElse("")
                        + owner.filter(parent -> parent instanceof MethodDeclaration)
                        .map(parent -> "." + ((MethodDeclaration) parent).getNameAsString())
                        .orElse("");
                for (String name : names) {
                    String key = ConfigKeys.normalize(prefix.isEmpty() ? name : prefix.get(0) + "." + name);
                    // Имя или значение записаны выражением, которое не прочитать: судить о свойстве нельзя
                    boolean unreadable = key.contains(UNKNOWN_PREFIX)
                            || having.stream().anyMatch(value -> value.startsWith(UNKNOWN_PREFIX));
                    if (having.isEmpty() || having.get(0).isEmpty() || unreadable) {
                        anyValue.add(key);
                        continue;
                    }
                    Switch found = switches.computeIfAbsent(key, unused -> new Switch());
                    found.byValue().computeIfAbsent(having.get(0), unused -> new TreeSet<>()).add(ownerName);
                    owner.ifPresent(node -> {
                        found.owners().add(node);
                        found.types().addAll(typesOf(node));
                    });
                }
            }
        }
    }

    // Под какими типами виден бин: тип @Bean-метода; сам класс и все, что он расширяет и реализует
    private Set<String> typesOf(Node owner) {
        Set<String> types = new HashSet<>();
        if (owner instanceof MethodDeclaration method) {
            types.add(LocalTypes.typeName(method.getType()));
        }
        if (owner instanceof ClassOrInterfaceDeclaration type) {
            types.add(type.getNameAsString());
            type.getImplementedTypes().forEach(parent -> types.add(parent.getNameAsString()));
            type.getExtendedTypes().forEach(parent -> types.add(parent.getNameAsString()));
            // Условие на классе настроек относится ко всем его бинам
            type.getMethods().stream()
                    .filter(method -> Annotations.has(method, BEAN))
                    .forEach(method -> types.add(LocalTypes.typeName(method.getType())));
        }
        return types;
    }

    /**
     * @return свойства, значение которых читает сам код: @Value("${bot.type}"), environment.getProperty("bot.type"),
     * условие-выражение, класс с @ConfigurationProperties. Что он делает с "лишним" значением, по аннотациям не видно
     */
    private Set<String> readByCode(List<SourceFile> sources, Set<String> keys) {
        Set<String> read = new HashSet<>();
        for (SourceFile source : sources) {
            for (StringLiteralExpr literal : source.findAll(StringLiteralExpr.class)) {
                Optional<AnnotationExpr> annotation = literal.findAncestor(AnnotationExpr.class);
                boolean condition = annotation
                        .filter(found -> CONDITIONAL.equals(found.getName().getIdentifier()))
                        .isPresent();
                if (condition || TestClasses.isInside(literal)) {
                    continue;
                }
                boolean properties = annotation
                        .filter(found -> CONFIGURATION_PROPERTIES.equals(found.getName().getIdentifier()))
                        .isPresent();
                Matcher name = PROPERTY_NAME.matcher(literal.getValue());
                while (name.find()) {
                    String candidate = ConfigKeys.normalize(name.group());
                    if (keys.contains(candidate)) {
                        read.add(candidate);
                    }
                    // @ConfigurationProperties("bot"): в поля класса попадает все, что начинается с bot.
                    if (properties) {
                        keys.stream().filter(key -> key.startsWith(candidate + ".")).forEach(read::add);
                    }
                }
            }
            for (AnnotationExpr annotation : source.findAll(AnnotationExpr.class)) {
                if (CONFIGURATION_PROPERTIES.equals(annotation.getName().getIdentifier())) {
                    AnnotationValues.expressions(annotation, PROPERTIES_PREFIX).stream()
                            .filter(value -> !value.isStringLiteralExpr())
                            // Приставка записана константой: какие свойства читает класс, неизвестно
                            .findAny()
                            .ifPresent(unknown -> read.addAll(keys));
                }
            }
        }
        return read;
    }

    /**
     * @return true, если бин того же типа создается и без этого условия: @ConditionalOnMissingBean либо обычный
     * компонент. Тогда значение, для которого условия нет, означает "взять вариант по умолчанию"
     */
    private boolean hasFallback(Switch found, List<SourceFile> sources) {
        for (SourceFile source : sources) {
            for (MethodDeclaration method : source.findAll(MethodDeclaration.class)) {
                if (Annotations.has(method, BEAN) && !TestClasses.isInside(method) && !isUnderCondition(method, found)
                        && found.types().contains(LocalTypes.typeName(method.getType()))) {
                    return true;
                }
            }
            for (ClassOrInterfaceDeclaration type : source.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface() || !Annotations.hasAny(type, STEREOTYPES) || TestClasses.isInside(type)
                        || isUnderCondition(type, found)) {
                    continue;
                }
                boolean sameType = found.types().contains(type.getNameAsString())
                        || type.getImplementedTypes().stream()
                        .anyMatch(parent -> found.types().contains(parent.getNameAsString()))
                        || type.getExtendedTypes().stream()
                        .anyMatch(parent -> found.types().contains(parent.getNameAsString()));
                if (sameType) {
                    return true;
                }
            }
        }
        return false;
    }

    // Условие стоит на самом методе или классе либо на классе, в котором они объявлены
    private boolean isUnderCondition(Node node, Switch found) {
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            if (found.owners().contains(current)) {
                return true;
            }
        }
        return false;
    }
}
