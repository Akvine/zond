package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ThisEscapeInConstructorRule extends AbstractRule {
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Set<String> IMMEDIATE_CALLS = Set.of(
            "forEach", "anyMatch", "allMatch", "noneMatch", "filter", "map", "flatMap", "mapToInt", "mapToLong",
            "mapToObj", "collect", "reduce", "peek", "sorted", "sort", "removeIf", "replaceAll", "computeIfAbsent",
            "computeIfPresent", "compute", "merge", "ifPresent", "ifPresentOrElse", "orElseGet", "min", "max",
            "takeWhile", "dropWhile");
    // Preconditions.checkState(valid, this), String.format("%s", this), log.debug("{}", this): объект только
    // печатают в сообщение и нигде не сохраняют
    private static final Set<String> OBSERVING_CALLS = Set.of(
            "checkState", "checkArgument", "checkNotNull", "checkElementIndex", "checkPositionIndex", "verify",
            "verifyNotNull", "requireNonNull", "format", "valueOf", "append", "trace", "debug", "info", "warn", "error",
            "println", "print", "printf");
    private static final int SNIPPET_LENGTH = 80;
    // Объект, созданный с this, начнет работать в другом потоке
    private static final Set<String> THREAD_TYPES = Set.of("Thread", "Timer");
    private static final String BUILD = "build";
    private static final Set<String> CONSTRUCT_TYPES = Set.of("Construct", "Stack", "NestedStack", "Stage", "Chart");

    @Override
    public String code() {
        return RuleCodes.THIS_ESCAPE_IN_CONSTRUCTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет утечку this из конструктора: передачу this, this::method или лямбды с this наружу";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ConstructorDeclaration constructor : sourceFile.unit().findAll(ConstructorDeclaration.class)) {
            // Одна лямбда может обращаться к this несколько раз - сообщаем о ней один раз
            Set<Node> reported = Collections.newSetFromMap(new IdentityHashMap<>());

            for (ThisExpr thisExpr : constructor.findAll(ThisExpr.class)) {
                // Outer.this и this внутри анонимного класса - это уже другой объект
                if (thisExpr.getTypeName().isPresent() || isInsideNestedClass(thisExpr, constructor)) {
                    continue;
                }

                Optional<Node> escape = findEscape(thisExpr, constructor).filter(reported::add);
                if (escape.isEmpty() || isScopeOfConstructTree(escape.get(), constructor)) {
                    continue;
                }
                String text = "Утечка this из конструктора '" + constructor.getNameAsString() + "' в '"
                        + snippet(escape.get()) + "': объект уходит наружу до завершения конструктора,"
                        + " другой поток может увидеть его недостроенным; публикуйте объект"
                        + " после создания, например через фабричный метод";
                violations.add(staysInside(escape.get(), constructor)
                        ? violation(sourceFile, escape.get(), text + ". Здесь ссылку получает объект-помощник, который"
                        + " остается в самом объекте, либо свой же метод: наружу она уйдет, только если тот сам ее"
                        + " опубликует, поэтому это подозрение").withConfidence(Confidence.SUSPICION)
                        : violation(sourceFile, escape.get(), text));
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
     * @return узел, через который this покидает конструктор, либо пусто
     */
    private Optional<Node> findEscape(ThisExpr thisExpr, ConstructorDeclaration constructor) {
        Node parent = thisExpr.getParentNode().orElse(null);

        // register(this), new Thread(this)
        if (isPassedOutside(thisExpr, parent)) {
            return Optional.of(thisExpr);
        }

        // other.owner = this, INSTANCE = this
        if (isAssignedOutside(thisExpr, parent)) {
            return Optional.of(thisExpr);
        }

        // subscribe(this::handle), other.callback = this::handle
        if (parent instanceof MethodReferenceExpr reference) {
            Node holder = reference.getParentNode().orElse(null);
            return isPassedOutside(reference, holder) || isAssignedOutside(reference, holder)
                    ? Optional.of(reference)
                    : Optional.empty();
        }

        // submit(() -> this.handle()): лямбда захватывает this и уходит наружу
        return findEnclosingLambda(thisExpr, constructor)
                .filter(lambda -> isPassedOutside(lambda, lambda.getParentNode().orElse(null)))
                .map(lambda -> lambda);
    }

    /**
     * Ссылка остается внутри объекта: ее получает помощник, которого кладут в свое поле или в локальную переменную
     * (changes = new PropertyChangeSupport(this)), свой же метод (setMatcher(this::matches)) либо строитель,
     * результат которого кладут в свое поле (routes = route().GET(PATH, this::get).build())
     */
    private boolean staysInside(Node escape, ConstructorDeclaration constructor) {
        Node holder = escape.getParentNode().orElse(null);
        if (holder instanceof ObjectCreationExpr creation) {
            return !THREAD_TYPES.contains(creation.getType().getNameAsString()) && isKept(creation);
        }
        if (!(holder instanceof MethodCallExpr call)) {
            return false;
        }
        boolean isOwnCall = call.getScope().map(scope -> scope.isThisExpr() || scope.isSuperExpr()).orElse(true);
        if (isOwnCall) {
            return isOwnMethod(call, constructor);
        }
        MethodCallExpr outermost = call;
        while (outermost.getParentNode().orElse(null) instanceof MethodCallExpr outer
                && outer.getScope().orElse(null) == outermost) {
            outermost = outer;
        }
        return outermost != call && BUILD.equals(outermost.getNameAsString()) && isKept(outermost);
    }

    // Результат выражения кладут в свое поле либо в локальную переменную
    private boolean isKept(Expression created) {
        Node target = created.getParentNode().orElse(null);
        return target instanceof VariableDeclarator
                || target instanceof AssignExpr assign && assign.getValue() == created && isOwnState(assign.getTarget(), true);
    }

    // Метод объявлен в самом классе либо достался от предка; имя из статического импорта - чужой метод
    private boolean isOwnMethod(MethodCallExpr call, ConstructorDeclaration constructor) {
        String name = call.getNameAsString();
        Optional<ClassOrInterfaceDeclaration> type = constructor.findAncestor(ClassOrInterfaceDeclaration.class);
        if (type.isEmpty()) {
            return false;
        }
        boolean isDeclared = type.get().getMethodsByName(name).stream().anyMatch(method -> !method.isStatic());
        boolean isImported = constructor.findCompilationUnit().stream()
                .flatMap(unit -> unit.getImports().stream())
                .anyMatch(imported -> imported.isStatic()
                        && (imported.isAsterisk() || imported.getNameAsString().endsWith("." + name)));
        return isDeclared || !type.get().getExtendedTypes().isEmpty() && !isImported;
    }

    // class Storage extends Construct { Storage(Construct scope, String id) { super(scope, id); new Bucket(this, "files"); } }:
    // в AWS CDK и cdk8s дерево ресурсов так и строят - в одном потоке, передавая родителя первым аргументом
    private boolean isScopeOfConstructTree(Node escape, ConstructorDeclaration constructor) {
        boolean isConstruct = constructor.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> type.getExtendedTypes().stream()
                        .anyMatch(parent -> CONSTRUCT_TYPES.contains(parent.getNameAsString())))
                .isPresent();
        return isConstruct && escape.getParentNode()
                .filter(holder -> holder instanceof NodeWithArguments<?> call && !call.getArguments().isEmpty()
                        && call.getArgument(0) == escape)
                .isPresent();
    }

    private boolean isPassedOutside(Expression value, Node parent) {
        if (parent instanceof ObjectCreationExpr creation) {
            return isArgument(value, creation.getArguments());
        }
        // listeners.add(this) на собственном поле - объект остается внутри себя. Стрим и коллекция вызывают
        // функцию сразу, в этом же потоке: ids.forEach(this::addMember) ничего наружу не выносит
        return parent instanceof MethodCallExpr call
                && isArgument(value, call.getArguments())
                && !OBSERVING_CALLS.contains(call.getNameAsString())
                && call.getScope().filter(scope -> isOwnState(scope, false)).isEmpty()
                && (value.isThisExpr() || !IMMEDIATE_CALLS.contains(call.getNameAsString()));
    }

    // Запись в локальную переменную this наружу не выносит
    private boolean isAssignedOutside(Expression value, Node parent) {
        return parent instanceof AssignExpr assign
                && assign.getValue() == value
                && !isOwnState(assign.getTarget(), true);
    }

    private boolean isArgument(Expression value, List<Expression> arguments) {
        return arguments.stream().anyMatch(argument -> argument == value);
    }

    // this.x либо нестатическое поле своего класса; локальные переменные и параметры - по флагу:
    // параметр конструктора - это чужой объект, вызов на нем выносит this наружу
    private boolean isOwnState(Expression expression, boolean localsAreOwn) {
        if (expression.isFieldAccessExpr()) {
            return expression.asFieldAccessExpr().getScope().isThisExpr();
        }
        if (!expression.isNameExpr()) {
            return false;
        }

        Optional<Node> declaration =
                LocalTypes.findDeclaration(expression, expression.asNameExpr().getNameAsString());
        if (declaration.isEmpty()) {
            return false;
        }

        Optional<FieldDeclaration> field = Optional.of(declaration.get())
                .filter(node -> node instanceof VariableDeclarator)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof FieldDeclaration)
                .map(parent -> (FieldDeclaration) parent);

        // Статическое поле видно всем потокам сразу
        return field.map(own -> !own.isStatic()).orElse(localsAreOwn);
    }

    private Optional<LambdaExpr> findEnclosingLambda(Node node, ConstructorDeclaration constructor) {
        LambdaExpr outermost = null;
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != constructor) {
            if (current instanceof LambdaExpr lambda) {
                outermost = lambda;
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.ofNullable(outermost);
    }

    private boolean isInsideNestedClass(Node node, ConstructorDeclaration constructor) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != constructor) {
            if (current instanceof BodyDeclaration<?>) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // Для сообщения берем выражение, в котором this уходит наружу: bus.register(this)
    private String snippet(Node escape) {
        String text = SPACES.matcher(escape.getParentNode().map(Node::toString).orElse(escape.toString()))
                .replaceAll(" ");
        return text.length() > SNIPPET_LENGTH ? text.substring(0, SNIPPET_LENGTH) + "..." : text;
    }
}
