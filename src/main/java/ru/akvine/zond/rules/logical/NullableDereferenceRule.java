package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
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
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

@Component
public class NullableDereferenceRule extends AbstractRule {
    private static final String GET = "get";
    private static final String OR_ELSE = "orElse";
    private static final String CONTAINS_KEY = "containsKey";
    private static final String KEY_SET = ".keySet()";
    private static final Set<String> MAP_TYPES =
            Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "ConcurrentMap");

    // Методы, которые возвращают null, если значения нет
    private static final Set<String> NULLABLE_SOURCES =
            Set.of("getParameter", "getHeader", "getAttribute", "getProperty", "getenv");
    // System.getProperty("os.name"): стандартные свойства JVM заданы всегда
    private static final String GET_PROPERTY = "getProperty";
    private static final Set<String> STANDARD_PROPERTIES = Set.of(
            "java.version", "java.vendor", "java.home", "java.vm.name", "java.vm.version", "java.vm.vendor",
            "java.specification.version", "java.class.version", "java.class.path", "java.io.tmpdir", "os.name",
            "os.arch", "os.version", "file.separator", "path.separator", "line.separator", "user.name", "user.home",
            "user.dir", "file.encoding", "native.encoding");

    @Override
    public String code() {
        return RuleCodes.NULLABLE_DEREFERENCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обращение к значению, которое может быть null, без проверки";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            Optional<String> source = variable.getInitializer().flatMap(this::describeNullableSource);
            Optional<Node> callable = Nodes.enclosingCallable(variable);
            if (source.isEmpty() || callable.isEmpty() || TestClasses.isInside(variable)
                    || isKeyChecked(variable) || isReassigned(variable, callable.get())) {
                continue;
            }

            String name = variable.getNameAsString();
            findUncheckedUse(variable, callable.get()).ifPresent(use -> violations.add(violation(sourceFile, use,
                    "'" + name + "' получена из " + source.get() + " и может быть null, а '" + use.getParentNode()
                            .map(Node::toString).orElse(name) + "' обращается к ней без проверки: будет"
                            + " NullPointerException; проверьте значение на null")));
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

    private Optional<String> describeNullableSource(Expression initializer) {
        Expression value = Nodes.unwrap(initializer);
        if (!value.isMethodCallExpr()) {
            return Optional.empty();
        }
        MethodCallExpr call = value.asMethodCallExpr();
        String method = call.getNameAsString();
        int arguments = call.getArguments().size();

        boolean isMapGet = GET.equals(method) && arguments == 1
                && call.getScope().filter(scope -> LocalTypes.isAnyOf(scope, MAP_TYPES, () -> false)).isPresent();
        if (isMapGet) {
            return Optional.of("Map.get(...)");
        }
        if (OR_ELSE.equals(method) && arguments == 1 && call.getArgument(0).isNullLiteralExpr()) {
            return Optional.of("orElse(null)");
        }
        if (GET_PROPERTY.equals(method) && arguments == 1 && call.getArgument(0).isStringLiteralExpr()
                && STANDARD_PROPERTIES.contains(call.getArgument(0).asStringLiteralExpr().getValue())) {
            return Optional.empty();
        }
        return NULLABLE_SOURCES.contains(method) && arguments == 1 ? Optional.of(method + "(...)") : Optional.empty();
    }

    // if (map.containsKey(key)) { value = map.get(key); ... } либо обход ключей: for (K key : map.keySet())
    private boolean isKeyChecked(VariableDeclarator variable) {
        if (isKeyFromKeySet(variable)) {
            return true;
        }
        Optional<String> map = variable.getInitializer()
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .flatMap(call -> call.asMethodCallExpr().getScope())
                .map(Expression::toString);
        return map.isPresent() && Guards.isGuarded(variable, check -> check.isMethodCallExpr()
                && CONTAINS_KEY.equals(check.asMethodCallExpr().getNameAsString())
                && check.asMethodCallExpr().getScope().filter(scope -> scope.toString().equals(map.get())).isPresent());
    }

    private boolean isKeyFromKeySet(VariableDeclarator variable) {
        Optional<MethodCallExpr> get = variable.getInitializer()
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> call.getScope().isPresent() && call.getArguments().size() == 1);
        if (get.isEmpty()) {
            return false;
        }
        String keys = get.get().getScope().get() + KEY_SET;
        // Set<String> profiles = map.keySet(); for (String profile : profiles) map.get(profile)
        Predicate<Expression> isKeySet = iterable -> iterable.toString().equals(keys)
                || LocalTypes.findInitializer(iterable).filter(initializer -> initializer.toString().equals(keys)).isPresent();
        String key = get.get().getArgument(0).toString();
        Node current = variable.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof ForEachStmt loop
                    && isKeySet.test(loop.getIterable())
                    && loop.getVariable().getVariable(0).getNameAsString().equals(key)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // if (lock != this) throw ...: после такой проверки lock - это this, а значит, не null
    private boolean isSameObjectCheck(Expression check, String name) {
        return check.isBinaryExpr()
                && (check.asBinaryExpr().getOperator() == BinaryExpr.Operator.EQUALS
                || check.asBinaryExpr().getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                && (isNamed(check.asBinaryExpr().getLeft(), name) && check.asBinaryExpr().getRight().isThisExpr()
                || isNamed(check.asBinaryExpr().getRight(), name) && check.asBinaryExpr().getLeft().isThisExpr());
    }

    private boolean isNamed(Expression expression, String name) {
        return expression.isNameExpr() && expression.asNameExpr().getNameAsString().equals(name);
    }

    // После нового присваивания переменная может уже не быть null
    private boolean isReassigned(VariableDeclarator variable, Node callable) {
        return callable.findAll(AssignExpr.class).stream()
                .anyMatch(assign -> assign.getTarget().toString().equals(variable.getNameAsString()));
    }

    // Первое обращение value.method() или value.field, до которого выполнение доходит без проверки на null
    private Optional<NameExpr> findUncheckedUse(VariableDeclarator variable, Node callable) {
        String name = variable.getNameAsString();
        for (NameExpr usage : callable.findAll(NameExpr.class)) {
            // Та же переменная, а не другая с тем же именем: for (Override override : ...) в соседнем цикле
            if (!usage.getNameAsString().equals(name)
                    || LocalTypes.findDeclaration(usage).filter(declaration -> declaration != variable).isPresent()) {
                continue;
            }
            Node parent = usage.getParentNode().orElse(null);
            boolean isDereference = parent instanceof MethodCallExpr call
                    && call.getScope().filter(scope -> scope == usage).isPresent()
                    || parent instanceof FieldAccessExpr;
            if (isDereference) {
                return Guards.isGuarded(usage,
                        check -> Guards.isNullCheck(check, name) || isSameObjectCheck(check, name))
                        ? Optional.empty()
                        : Optional.of(usage);
            }
            // Переменную передали в проверку вне условия: Objects.requireNonNull(value), Assert.notNull(value, ...)
            if (parent instanceof Expression expression && Guards.isNullCheck(expression, name)) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
