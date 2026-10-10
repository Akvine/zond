package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.BeanScopes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Самодельный кеш: карта в поле объекта, который живет все время работы приложения. В нее добавляют записи
 * по ключам из данных, но никогда не удаляют - она растет, пока не закончится память.
 */
@Component
public class UnboundedMapCacheRule extends AbstractRule implements ProjectRule {
    private static final Set<String> MAP_TYPES = Set.of("Map", "ConcurrentMap", "HashMap", "ConcurrentHashMap",
            "LinkedHashMap", "TreeMap", "Hashtable", "ConcurrentSkipListMap", "SortedMap", "NavigableMap");
    // WeakHashMap и карты с вытеснением чистят себя сами: растущими считаются только обычные
    private static final Set<String> PLAIN_MAPS = Set.of(
            "HashMap", "ConcurrentHashMap", "LinkedHashMap", "TreeMap", "Hashtable", "ConcurrentSkipListMap");

    private static final Set<String> GROW_METHODS = Set.of("put", "putIfAbsent", "computeIfAbsent", "compute", "merge");
    private static final Set<String> SHRINK_METHODS = Set.of(
            "remove", "clear", "removeIf", "retainAll", "pollFirstEntry", "pollLastEntry", "removeAll");

    // Ключей такого типа конечное число: карта не вырастет больше него
    private static final Set<String> BOUNDED_KEYS = Set.of(
            "Boolean", "Class", "DayOfWeek", "Month", "Locale", "HttpStatus", "HttpMethod", "TimeUnit", "ChronoUnit",
            "Level", "Charset", "ZoneId");
    private static final Pattern ENUM_LIKE_KEY = Pattern.compile(".*(Type|Kind|Status|State|Mode|Level|Enum|Category)$");
    private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    // Методы, которые наполняют реестр обработчиков либо читают справочник из файла: набор ключей там задан заранее
    private static final Pattern REGISTRATION = Pattern.compile(
            "^(register|unregister|init|load|reload|refresh|read|parse|configure|setup|subscribe|fill|populate"
                    + "|add\\w*(Handler|Listener|Strategy|Processor|Provider|Converter|Factory|Resolver|Validator|Executor))\\w*");

    @Override
    public String code() {
        return RuleCodes.UNBOUNDED_MAP_CACHE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет карты в бинах-одиночках и статических полях, в которые только добавляют записи";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Set<String> enums = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            sourceFile.unit().findAll(EnumDeclaration.class).forEach(found -> enums.add(found.getNameAsString()));
        }
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!type.isInterface()) {
                    check(sourceFile, type, enums, violations);
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
        return ErrorType.RESOURCE;
    }

    private void check(SourceFile sourceFile, ClassOrInterfaceDeclaration type, Set<String> enums, List<Violation> violations) {
        boolean singleton = BeanScopes.isSingletonBean(type);
        for (FieldDeclaration field : type.getFields()) {
            // Карта в обычном объекте исчезает вместе с ним; чужой код до закрытого поля не дотянется,
            // а открытое могут чистить снаружи
            if (!(field.isStatic() || singleton) || !field.isPrivate()) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                if (!MAP_TYPES.contains(LocalTypes.typeName(variable.getType())) || hasBoundedKey(variable.getType(), enums)) {
                    continue;
                }
                String name = variable.getNameAsString();
                if (!isPlainMap(variable, type) || isCleanedOrExposed(type, name)) {
                    continue;
                }
                growthOf(type, name).ifPresent(growth -> violations.add(violation(sourceFile, variable,
                        "В карту '" + name + "' " + (field.isStatic() ? "(статическое поле)" : "бина-одиночки")
                                + " записи только добавляют ('" + growth.getNameAsString() + "' в методе '"
                                + methodName(growth) + "'), а удаления и ограничения размера нет: с каждым новым"
                                + " ключом она растет, пока не закончится память; замените ее кешем с ограничением"
                                + " (Caffeine, @Cacheable со сроком жизни) либо удаляйте устаревшие записи")));
            }
        }
    }

    // Карта создана здесь же обычным конструктором: готовую карту из библиотеки (Caffeine.asMap()) не трогаем
    private boolean isPlainMap(VariableDeclarator variable, ClassOrInterfaceDeclaration type) {
        List<Expression> values = new ArrayList<>();
        variable.getInitializer().ifPresent(values::add);
        type.getConstructors().forEach(constructor -> constructor.findAll(AssignExpr.class).stream()
                .filter(assignment -> isField(assignment.getTarget(), variable.getNameAsString()))
                .forEach(assignment -> values.add(assignment.getValue())));
        return !values.isEmpty() && values.stream().map(Nodes::unwrap).allMatch(value ->
                value.isObjectCreationExpr() && isPlain(value.asObjectCreationExpr()));
    }

    // new LinkedHashMap<>() { removeEldestEntry ... } вытесняет старые записи сама
    private boolean isPlain(ObjectCreationExpr creation) {
        return PLAIN_MAPS.contains(creation.getType().getNameAsString()) && creation.getAnonymousClassBody().isEmpty();
    }

    private boolean hasBoundedKey(Type type, Set<String> enums) {
        if (!type.isClassOrInterfaceType()) {
            return false;
        }
        return type.asClassOrInterfaceType().getTypeArguments()
                .filter(arguments -> !arguments.isEmpty())
                .map(arguments -> LocalTypes.typeName(arguments.get(0)))
                .filter(key -> BOUNDED_KEYS.contains(key) || enums.contains(key) || ENUM_LIKE_KEY.matcher(key).matches())
                .isPresent();
    }

    // Записи удаляют, карту заменяют новой либо отдают наружу - тогда чистить ее может кто угодно
    private boolean isCleanedOrExposed(ClassOrInterfaceDeclaration type, String name) {
        boolean shrinks = type.findAll(MethodCallExpr.class).stream()
                .filter(call -> SHRINK_METHODS.contains(call.getNameAsString()))
                .anyMatch(call -> isField(root(call), name));
        boolean replaced = type.getMethods().stream()
                .flatMap(method -> method.findAll(AssignExpr.class).stream())
                .anyMatch(assignment -> isField(assignment.getTarget(), name));
        boolean returned = type.findAll(ReturnStmt.class).stream()
                .anyMatch(statement -> statement.getExpression().filter(value -> isField(value, name)).isPresent());
        boolean passed = type.findAll(MethodCallExpr.class).stream()
                .flatMap(call -> call.getArguments().stream())
                .anyMatch(argument -> isField(argument, name));
        return shrinks || replaced || returned || passed;
    }

    /**
     * @return вызов, который добавляет запись по ключу из данных во время работы приложения
     */
    private Optional<MethodCallExpr> growthOf(ClassOrInterfaceDeclaration type, String name) {
        return type.findAll(MethodCallExpr.class).stream()
                .filter(call -> GROW_METHODS.contains(call.getNameAsString()) && !call.getArguments().isEmpty())
                .filter(call -> call.getScope().filter(scope -> isField(scope, name)).isPresent())
                .filter(call -> !hasConstantKey(call) && !CodeContexts.isStartup(call))
                .filter(call -> !REGISTRATION.matcher(methodName(call)).matches())
                .findFirst();
    }

    // map.put("default", ...), map.put(Kind.FAST, ...), map.put(DEFAULT_KEY, ...): таких ключей конечное число
    private boolean hasConstantKey(MethodCallExpr call) {
        Expression key = Nodes.unwrap(call.getArgument(0));
        if (key.isLiteralExpr() || key.isClassExpr()) {
            return true;
        }
        String last = key.isFieldAccessExpr() ? key.asFieldAccessExpr().getNameAsString() : key.toString();
        return (key.isNameExpr() || key.isFieldAccessExpr()) && CONSTANT_NAME.matcher(last).matches();
    }

    // map.entrySet().removeIf(...) -> map: объект, с которого начинается цепочка вызовов
    private Expression root(MethodCallExpr call) {
        Expression current = call;
        while (current.isMethodCallExpr() && current.asMethodCallExpr().getScope().isPresent()) {
            current = Nodes.unwrap(current.asMethodCallExpr().getScope().get());
        }
        return current;
    }

    private boolean isField(Expression expression, String name) {
        Expression value = Nodes.unwrap(expression);
        if (value.isNameExpr()) {
            return value.asNameExpr().getNameAsString().equals(name);
        }
        return value.isFieldAccessExpr()
                && value.asFieldAccessExpr().getScope().isThisExpr()
                && value.asFieldAccessExpr().getNameAsString().equals(name);
    }

    private String methodName(Node node) {
        return node.findAncestor(MethodDeclaration.class).map(MethodDeclaration::getNameAsString).orElse("");
    }
}
