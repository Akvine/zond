package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Метод принимает набор своих объектов - массив или список, - а работает только с первым: единственное обращение
 * к элементам - {@code items[0]} или {@code items.get(0)}, перебора нет. Пока вызывающий передает один элемент,
 * все работает; как только их приходит несколько, остальные молча теряются. Так выглядит обработчик пачки
 * сообщений, написанный по образцу обработчика одного сообщения.
 * <p>
 * Методы, которые первый элемент выбирают по своему назначению (first, pick, resolve, to...), не трогаем
 */
@Component
public class OnlyFirstElementProcessedRule extends AbstractRule implements ProjectRule {
    private static final Set<String> COLLECTIONS = Set.of("List", "Collection", "ArrayList", "LinkedList", "Set");
    private static final String LENGTH = "length";
    private static final Set<String> SIZE_METHODS = Set.of("size", "isEmpty");
    private static final String GET = "get";
    private static final String FIRST_INDEX = "0";
    private static final Set<String> FIRST_METHODS = Set.of("getFirst", "firstElement", "element", "peekFirst");
    // items.iterator().next(), items.stream().findFirst()
    private static final Set<String> VIEW_METHODS = Set.of("iterator", "stream", "listIterator");
    private static final Set<String> FIRST_OF_VIEW = Set.of("next", "findFirst", "findAny");
    // ArrayUtils.isEmpty(items), Objects.requireNonNull(items): проверка самого набора, а не работа с элементами
    private static final Set<String> CHECK_METHODS = Set.of(
            "isEmpty", "isNotEmpty", "requireNonNull", "notNull", "notEmpty", "nonNull", "isNull", "getLength",
            "noNullElements");

    // Набор, в котором у каждого места своя роль: первый элемент там берут по смыслу
    private static final Pattern POSITIONAL_NAME = Pattern.compile(
            "(?i)args|arguments|argv|params|parameters|opts|options|parts|tokens|segments|chunks|pieces|values|"
                    + "coords|coordinates|bounds|pair|tuple|fields|columns|cells|groups|path|paths|keys");
    private static final Pattern PLURAL_NAME = Pattern.compile(".*[^s]s|.*(List|Array|Batch|Collection|Set|Items)");
    // Метод по назначению выбирает один элемент либо строит из набора что-то другое: первое слово имени
    private static final Set<String> SELECTOR_WORDS = Set.of(
            "first", "head", "peek", "pick", "choose", "select", "single", "any", "primary", "main", "latest", "last",
            "newest", "oldest", "top", "best", "default", "resolve", "find", "get", "extract", "to", "as", "from", "of",
            "convert", "map", "build", "create", "make", "parse", "merge", "combine", "join", "reduce", "is", "has",
            "can", "validate", "check", "compare", "test", "supports", "matches", "equals", "describe", "format",
            "print", "render", "sum", "count", "min", "max", "avg", "average", "calculate", "compute", "determine",
            "detect", "lookup", "load", "read", "fetch");
    private static final Pattern SELECTOR_SUFFIX = Pattern.compile(".*(First|Head|Single|Any|Primary|Main|One)");
    // Метод по назначению обрабатывает все, что ему передали
    private static final Set<String> HANDLER_WORDS = Set.of(
            "on", "handle", "process", "consume", "receive", "listen", "accept", "execute", "run", "perform", "apply",
            "save", "store", "persist", "send", "publish", "notify", "dispatch", "update", "delete", "remove",
            "import", "sync", "upload", "register", "add", "insert", "write", "push", "submit", "schedule", "enqueue",
            "do");
    private static final Set<String> SINGLE_ELEMENT_FACTORIES =
            Set.of("of", "asList", "singletonList", "singleton", "newArrayList");
    private static final Set<UnaryExpr.Operator> STEPS = Set.of(
            UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT,
            UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT);
    private static final String MAIN = "main";

    private enum Use {
        FIRST, SIZE, OTHER
    }

    @Override
    public String code() {
        return RuleCodes.ONLY_FIRST_ELEMENT_PROCESSED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет методы, которые принимают набор своих объектов, а обрабатывают только"
                + " первый элемент";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
                String name = method.getNameAsString();
                if (method.getBody().isEmpty() || TestClasses.isInside(method) || MAIN.equals(name)
                        || isSelector(name) || !performsAction(method)) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    Optional<ClassOrInterfaceType> element = elementType(parameter)
                            .filter(type -> classes.resolve(type).isPresent());
                    if (element.isEmpty() || !isBatchName(parameter.getNameAsString())) {
                        continue;
                    }
                    Optional<Node> first = onlyFirstElementUse(method, parameter);
                    if (first.isEmpty() || isAlwaysCalledWithOne(method, parameter, graph)) {
                        continue;
                    }
                    Violation violation = violation(sourceFile, first.get(),
                            "Метод '" + name + "' принимает набор '" + parameter.getNameAsString() + "' ("
                                    + parameter.getType() + "), а работает только с первым элементом: '" + first.get()
                                    + "' - единственное обращение к элементам, перебора нет. Когда вызывающий"
                                    + " передаст несколько элементов, остальные будут молча потеряны; переберите"
                                    + " все элементы либо принимайте один");
                    violations.add(startsWithWord(name, HANDLER_WORDS)
                            ? violation
                            : violation.withConfidence(Confidence.SUSPICION));
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
        return ErrorType.LOGICAL;
    }

    // Update[] updates либо List<Update> updates; у String... parts вызывающий обычно и передает одно значение
    private Optional<ClassOrInterfaceType> elementType(Parameter parameter) {
        Type type = parameter.getType();
        if (parameter.isVarArgs()) {
            return Optional.empty();
        }
        if (type.isArrayType()) {
            return Optional.of(type.asArrayType().getComponentType())
                    .filter(Type::isClassOrInterfaceType)
                    .map(Type::asClassOrInterfaceType);
        }
        if (!type.isClassOrInterfaceType() || !COLLECTIONS.contains(type.asClassOrInterfaceType().getNameAsString())) {
            return Optional.empty();
        }
        return type.asClassOrInterfaceType().getTypeArguments()
                .filter(arguments -> arguments.size() == 1 && arguments.get(0).isClassOrInterfaceType())
                .map(arguments -> arguments.get(0).asClassOrInterfaceType());
    }

    private boolean isSelector(String name) {
        return startsWithWord(name, SELECTOR_WORDS) || SELECTOR_SUFFIX.matcher(name).matches();
    }

    // onUpdate, handle, saveAll: имя - само слово либо слово, за которым идет следующее с заглавной буквы
    private boolean startsWithWord(String name, Set<String> words) {
        int end = 0;
        while (end < name.length() && Character.isLowerCase(name.charAt(end))) {
            end++;
        }
        return words.contains(name.substring(0, end));
    }

    private boolean isBatchName(String name) {
        return PLURAL_NAME.matcher(name).matches() && !POSITIONAL_NAME.matcher(name).matches();
    }

    // Метод что-то делает, а не только вычисляет значение из первого элемента: в нем есть вызов отдельной строкой
    private boolean performsAction(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getParentNode().filter(ExpressionStmt.class::isInstance).isPresent())
                .anyMatch(call -> !Loggers.isLogCall(call));
    }

    /**
     * @return первое обращение к первому элементу, если кроме таких обращений и проверок размера с параметром
     * ничего не делают; пусто, если набор перебирают, передают дальше, пишут в него либо берут другие элементы
     */
    private Optional<Node> onlyFirstElementUse(MethodDeclaration method, Parameter parameter) {
        Node first = null;
        for (NameExpr usage : method.getBody().get().findAll(NameExpr.class)) {
            if (!usage.getNameAsString().equals(parameter.getNameAsString())
                    || LocalTypes.findDeclaration(usage).filter(declaration -> declaration == parameter).isEmpty()) {
                continue;
            }
            Optional<Node> element = elementAccess(usage);
            Use use = element.isPresent() ? Use.FIRST : classify(usage);
            if (use == Use.OTHER || element.filter(this::isWritten).isPresent()) {
                return Optional.empty();
            }
            if (first == null && element.isPresent()) {
                first = element.get();
            }
        }
        return Optional.ofNullable(first);
    }

    // items[0], items.get(0), items.getFirst(), items.iterator().next(), items.stream().findFirst()
    private Optional<Node> elementAccess(NameExpr usage) {
        Node parent = usage.getParentNode().orElse(null);
        if (parent instanceof ArrayAccessExpr access && access.getName() == usage) {
            return FIRST_INDEX.equals(access.getIndex().toString()) ? Optional.of(access) : Optional.empty();
        }
        if (!(parent instanceof MethodCallExpr call) || call.getScope().filter(scope -> scope == usage).isEmpty()) {
            return Optional.empty();
        }
        String name = call.getNameAsString();
        boolean byIndex = GET.equals(name) && call.getArguments().size() == 1
                && FIRST_INDEX.equals(call.getArgument(0).toString());
        if (byIndex || FIRST_METHODS.contains(name) && call.getArguments().isEmpty()) {
            return Optional.of(call);
        }
        if (VIEW_METHODS.contains(name) && call.getArguments().isEmpty()) {
            return call.getParentNode()
                    .filter(next -> next instanceof MethodCallExpr following
                            && following.getScope().filter(scope -> scope == call).isPresent()
                            && FIRST_OF_VIEW.contains(following.getNameAsString()));
        }
        return Optional.empty();
    }

    private Use classify(NameExpr usage) {
        Node parent = usage.getParentNode().orElse(null);
        // items[i], items[1]: берут не только первый
        if (parent instanceof ArrayAccessExpr) {
            return Use.OTHER;
        }
        if (parent instanceof FieldAccessExpr field) {
            return LENGTH.equals(field.getNameAsString()) ? Use.SIZE : Use.OTHER;
        }
        if (parent instanceof BinaryExpr comparison) {
            Expression other = comparison.getLeft() == usage ? comparison.getRight() : comparison.getLeft();
            return Nodes.unwrap(other).isNullLiteralExpr() ? Use.SIZE : Use.OTHER;
        }
        if (parent instanceof MethodCallExpr call) {
            if (call.getScope().filter(scope -> scope == usage).isPresent()) {
                return SIZE_METHODS.contains(call.getNameAsString()) ? Use.SIZE : Use.OTHER;
            }
            // Аргумент проверки либо записи в лог: с элементами при этом ничего не делают
            return CHECK_METHODS.contains(call.getNameAsString()) || Loggers.isLogCall(call) ? Use.SIZE : Use.OTHER;
        }
        return Use.OTHER;
    }

    // holder[0] = value, counter[0]++: массив из одного элемента служит изменяемой ячейкой
    private boolean isWritten(Node element) {
        Node parent = element.getParentNode().orElse(null);
        return parent instanceof AssignExpr assignment && assignment.getTarget() == element
                || parent instanceof UnaryExpr unary && STEPS.contains(unary.getOperator());
    }

    // Все вызовы в проекте передают ровно один элемент: List.of(order), new Order[] {order}
    private boolean isAlwaysCalledWithOne(MethodDeclaration method, Parameter parameter, CallGraph graph) {
        int index = method.getParameters().indexOf(parameter);
        List<CallGraph.Call> calls = graph.callsTo(method);
        return !calls.isEmpty() && calls.stream().allMatch(call -> index < call.site().getArguments().size()
                && isSingleElement(Nodes.unwrap(call.site().getArgument(index))));
    }

    private boolean isSingleElement(Expression argument) {
        if (argument.isArrayCreationExpr()) {
            return argument.asArrayCreationExpr().getInitializer()
                    .filter(initializer -> initializer.getValues().size() == 1)
                    .isPresent();
        }
        if (!argument.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = argument.asMethodCallExpr();
        return SINGLE_ELEMENT_FACTORIES.contains(call.getNameAsString()) && call.getArguments().size() == 1;
    }
}
