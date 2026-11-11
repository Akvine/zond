package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class OptionalMisuseRule extends AbstractRule {
    private static final String OPTIONAL = "Optional";
    private static final String GET = "get";
    private static final Set<String> PRESENCE_CHECKS = Set.of("isPresent", "isEmpty");

    // Методы, которые возвращают Optional: вызов get() сразу после них ничем не защищен
    private static final Set<String> OPTIONAL_SOURCES = Set.of("findFirst", "findAny", "findById", "ofNullable");
    // Операции Optional, после которых значение есть, только если оно было и до них
    private static final Set<String> PRESERVING = Set.of("map", "flatMap", "filter");
    private static final int DERIVATION_DEPTH = 3;
    private static final Set<String> NON_EMPTY_TERMINALS = Set.of("min", "max", "findFirst", "findAny", "average");
    // OptionalInt, OptionalLong, OptionalDouble: итоги числовых стримов
    private static final Set<String> PRIMITIVE_GETS = Set.of("getAsInt", "getAsLong", "getAsDouble");
    private static final Set<String> PRIMITIVE_SOURCES = Set.of("max", "min", "average", "findFirst", "findAny", "reduce");
    private static final Set<String> SIZE_PRESERVING = Set.of(
            "map", "mapToInt", "mapToLong", "mapToDouble", "mapToObj", "boxed", "sorted", "distinct", "peek", "parallel",
            "sequential", "unordered");
    private static final String STREAM = "stream";
    private static final Set<String> EMPTINESS_CHECKS = Set.of("isEmpty", "size", "isNotEmpty", "isNullOrEmpty");
    private static final Set<String> ADDS = Set.of("add", "addAll", "push", "offer");
    private static final Set<String> CATCHING = Set.of("NoSuchElementException", "RuntimeException", "Exception", "Throwable");

    @Override
    public String code() {
        return RuleCodes.OPTIONAL_MISUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Optional.get() без проверки и return null из методов, возвращающих Optional";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (!OPTIONAL.equals(LocalTypes.typeName(method.getType()))) {
                continue;
            }
            for (ReturnStmt returnStmt : method.findAll(ReturnStmt.class)) {
                boolean returnsNull = returnStmt.getExpression().filter(Expression::isNullLiteralExpr).isPresent();
                if (returnsNull && !Nodes.isInNestedScope(returnStmt, method)) {
                    violations.add(violation(sourceFile, returnStmt,
                            "Метод '" + method.getNameAsString() + "' возвращает Optional, но отдает null:"
                                    + " вызывающий код получит NullPointerException; верните Optional.empty()"));
                }
            }
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (isUncheckedPrimitiveGet(call)) {
                violations.add(violation(sourceFile, call.getName(),
                        "'" + call.getNameAsString() + "()' без проверки сразу после '"
                                + call.getScope()
                                .map(scope -> Nodes.unwrap(scope).asMethodCallExpr().getNameAsString())
                                .orElse("")
                                + "()': у пустого стрима итога нет, и вызов закончится NoSuchElementException;"
                                + " используйте orElse(...), orElseThrow(...) либо ifPresent(...)"));
            }
            if (!GET.equals(call.getNameAsString()) || !call.getArguments().isEmpty() || call.getScope().isEmpty()) {
                continue;
            }

            Expression scope = Nodes.unwrap(call.getScope().get());
            // get() внутри try, который ловит NoSuchElementException, - отсутствие значения уже обработано
            if ((isUncheckedSource(scope) || isUncheckedVariable(scope, call)) && !isCaught(call)
                    && !isOverNonEmptyCollection(scope, call)) {
                violations.add(violation(sourceFile, call,
                        "Optional.get() без проверки в '" + call + "': на пустом Optional будет"
                                + " NoSuchElementException; используйте orElseThrow(), orElse() или ifPresent()"));
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

    // values.stream().mapToInt(...).max().getAsInt(): пустой ли стрим, перед вызовом никто не проверил
    private boolean isUncheckedPrimitiveGet(MethodCallExpr call) {
        if (!PRIMITIVE_GETS.contains(call.getNameAsString()) || !call.getArguments().isEmpty() || call.getScope().isEmpty()) {
            return false;
        }
        Expression scope = Nodes.unwrap(call.getScope().get());
        // reduce(начальное значение, операция) возвращает само значение, а не OptionalInt
        boolean source = scope.isMethodCallExpr() && PRIMITIVE_SOURCES.contains(scope.asMethodCallExpr().getNameAsString())
                && scope.asMethodCallExpr().getArguments().size() <= 1;
        return source && !isCaught(call) && !isOverNonEmptyCollection(scope, call);
    }

    private boolean isCaught(Node node) {
        Node child = node;
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof CallableDeclaration<?>)) {
            if (current instanceof TryStmt tryStatement && tryStatement.getTryBlock() == child
                    && tryStatement.getCatchClauses().stream()
                    .anyMatch(clause -> CATCHING.stream().anyMatch(clause.getParameter().getType().asString()::contains))) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * list.stream().min(...).get() внутри if (!list.isEmpty()) либо сразу после list.add(...): у потока непустой
     * коллекции min, max, findFirst и findAny значение есть. filter и подобные по дороге могут его отнять
     */
    private boolean isOverNonEmptyCollection(Expression scope, MethodCallExpr call) {
        if (!scope.isMethodCallExpr() || !NON_EMPTY_TERMINALS.contains(scope.asMethodCallExpr().getNameAsString())) {
            return false;
        }
        Expression current = scope.asMethodCallExpr().getScope().map(Nodes::unwrap).orElse(null);
        while (current instanceof MethodCallExpr step && !STREAM.equals(step.getNameAsString())) {
            if (!SIZE_PRESERVING.contains(step.getNameAsString())) {
                return false;
            }
            current = step.getScope().map(Nodes::unwrap).orElse(null);
        }
        if (!(current instanceof MethodCallExpr stream) || stream.getScope().isEmpty()) {
            return false;
        }
        String collection = Nodes.unwrap(stream.getScope().get()).toString();
        return Guards.isGuarded(call, check -> isEmptinessCheck(check, collection)) || isFilledBefore(call, collection);
    }

    // list.isEmpty(), list.size(), CollectionUtils.isNotEmpty(list)
    private boolean isEmptinessCheck(Expression expression, String collection) {
        if (!expression.isMethodCallExpr() || !EMPTINESS_CHECKS.contains(expression.asMethodCallExpr().getNameAsString())) {
            return false;
        }
        MethodCallExpr check = expression.asMethodCallExpr();
        return check.getScope().map(scope -> Nodes.unwrap(scope).toString()).filter(collection::equals).isPresent()
                || check.getArguments().stream().anyMatch(argument -> Nodes.unwrap(argument).toString().equals(collection));
    }

    // matchedFileNames.add(name); ... matchedFileNames.stream().findFirst().get()
    private boolean isFilledBefore(MethodCallExpr call, String collection) {
        int line = call.getBegin().map(position -> position.line).orElse(0);
        return Nodes.enclosingCallable(call).stream()
                .flatMap(callable -> callable.findAll(MethodCallExpr.class).stream())
                .filter(add -> ADDS.contains(add.getNameAsString()))
                .filter(add -> add.getBegin().map(position -> position.line).orElse(Integer.MAX_VALUE) < line)
                .anyMatch(add -> add.getScope().map(scope -> Nodes.unwrap(scope).toString()).filter(collection::equals)
                        .isPresent());
    }

    // stream.findFirst().get(), repository.findById(id).get()
    private boolean isUncheckedSource(Expression scope) {
        return scope.isMethodCallExpr() && OPTIONAL_SOURCES.contains(scope.asMethodCallExpr().getNameAsString());
    }

    // Переменная типа Optional, до get() которой выполнение доходит без isPresent() / isEmpty(),
    // в том числе через цепочку: value.filter(...).isPresent()
    private boolean isUncheckedVariable(Expression scope, MethodCallExpr call) {
        if (LocalTypes.typeOf(scope).filter(OPTIONAL::equals).isEmpty()) {
            return false;
        }
        String variable = scope.toString();
        // Проверка производного значения - это и проверка исходного: column = table.flatMap(...) не пуст,
        // только когда не пуст table
        Set<String> names = withDerived(variable, call);
        if (isInsideOwnChain(call, names)) {
            return false;
        }
        return !Guards.isGuarded(call, check -> isPresenceCheckOfAny(check, names));
    }

    private boolean isPresenceCheckOfAny(Expression check, Set<String> names) {
        for (String name : names) {
            if (isPresenceCheck(check, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return сама переменная и все, что получено из нее через map, flatMap и filter - напрямую либо по цепочке:
     * format = index.flatMap(...), expected = format.map(...)
     */
    private Set<String> withDerived(String variable, Node usage) {
        Set<String> names = new LinkedHashSet<>();
        names.add(variable);
        List<VariableDeclarator> declarators = Nodes.enclosingCallable(usage)
                .map(callable -> callable.findAll(VariableDeclarator.class))
                .orElse(List.of());
        // Производные от производных: каждый проход добавляет следующее звено цепочки
        for (int pass = 0; pass < DERIVATION_DEPTH; pass++) {
            for (VariableDeclarator declarator : declarators) {
                boolean derived = declarator.getInitializer()
                        .flatMap(this::optionalRoot)
                        .filter(names::contains)
                        .isPresent();
                if (derived) {
                    names.add(declarator.getNameAsString());
                }
            }
        }
        return names;
    }

    // table.flatMap(...).filter(...).map(...) -> table; пусто, если выражение - не такая цепочка
    private Optional<String> optionalRoot(Expression expression) {
        Expression current = Nodes.unwrap(expression);
        boolean chained = false;
        while (current.isMethodCallExpr() && PRESERVING.contains(current.asMethodCallExpr().getNameAsString())
                && current.asMethodCallExpr().getScope().isPresent()) {
            current = Nodes.unwrap(current.asMethodCallExpr().getScope().get());
            chained = true;
        }
        return chained && current.isNameExpr() ? Optional.of(current.asNameExpr().getNameAsString()) : Optional.empty();
    }

    // block.flatMap(...).filter(parent -> block.get()...): лямбда вызывается, только когда значение есть
    private boolean isInsideOwnChain(Node usage, Set<String> names) {
        Optional<LambdaExpr> lambda = usage.findAncestor(LambdaExpr.class);
        while (lambda.isPresent()) {
            boolean ownChain = lambda.get().getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr)
                    .map(parent -> (Expression) parent)
                    .flatMap(this::optionalRoot)
                    .filter(names::contains)
                    .isPresent();
            if (ownChain) {
                return true;
            }
            lambda = lambda.get().findAncestor(LambdaExpr.class);
        }
        return false;
    }

    private boolean isPresenceCheck(Expression expression, String variable) {
        if (!expression.isMethodCallExpr()
                || !PRESENCE_CHECKS.contains(expression.asMethodCallExpr().getNameAsString())) {
            return false;
        }
        return expression.asMethodCallExpr().getScope()
                .map(checked -> Nodes.unwrap(checked).toString())
                .filter(checked -> checked.equals(variable) || checked.startsWith(variable + "."))
                .isPresent();
    }
}
