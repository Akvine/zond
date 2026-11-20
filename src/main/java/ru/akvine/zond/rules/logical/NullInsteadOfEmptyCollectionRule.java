package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.flow.FlowLibrary;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class NullInsteadOfEmptyCollectionRule extends AbstractRule {
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "List", "Set", "Map", "Collection", "Iterable", "Queue", "Deque", "SortedSet", "SortedMap", "Stream");
    private static final String ARRAY_SUFFIX = "[]";
    private static final String NULLABLE = "Nullable";
    private static final Pattern RETURNS_NULL = Pattern.compile("@return\\b[^@]*\\bnull\\b");
    private static final String FILTER = "filter";
    // filter(Objects::nonNull), filter(value -> value != null)
    private static final Pattern NOT_NULL_FILTER = Pattern.compile("nonNull|!= null");
    // Objects.requireNonNull(x), Optional.ofNullable(x), ObjectUtils.defaultIfNull(x, other), emptyIfNull(x)
    private static final Pattern NULL_HANDLERS = Pattern.compile(
            "requireNonNull\\w*|ofNullable|defaultIfNull|firstNonNull|emptyIfNull|nullToEmpty|\\w*OrEmpty");

    @Override
    public String code() {
        return RuleCodes.NULL_INSTEAD_OF_EMPTY_COLLECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет return null в методах, которые возвращают коллекцию или массив";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
            String type = LocalTypes.typeName(method.getType());
            boolean returnsCollection = COLLECTION_TYPES.contains(type) || type.endsWith(ARRAY_SUFFIX);
            if (!returnsCollection || documentsNull(method) || TestClasses.isInside(method)
                    || isCheckedByEveryCaller(method)) {
                continue;
            }
            for (ReturnStmt returned : method.findAll(ReturnStmt.class)) {
                boolean returnsNull = returned.getExpression().filter(value -> value.isNullLiteralExpr()).isPresent();
                if (returnsNull && !Nodes.isInNestedScope(returned, method) && !isNullPassThrough(returned, method)) {
                    violations.add(violation(sourceFile, returned,
                            "Метод '" + method.getNameAsString() + "' возвращает null вместо пустого значения:"
                                    + " каждый, кто его вызывает, обязан проверять результат, и первый забывший"
                                    + " получит NullPointerException; возвращайте пустую коллекцию"));
                }
            }
        }
        return violations;
    }

    // @Nullable либо "@return границы диапазона; null, если сравнение истинным быть не может": null - часть
    // описанного договора, у него свой смысл, и пустой массив его не заменит
    private boolean documentsNull(MethodDeclaration method) {
        return Annotations.has(method, NULLABLE) || method.getJavadocComment()
                .map(comment -> comment.getContent())
                .filter(text -> RETURNS_NULL.matcher(text).find())
                .isPresent();
    }

    // Закрытый метод, результат которого проверяют на null в каждом месте вызова: null здесь - условный знак
    // «значения нет», о котором знает весь вызывающий код (он в этом же файле). Забыть проверку некому
    private boolean isCheckedByEveryCaller(MethodDeclaration method) {
        Optional<CompilationUnit> unit = method.findCompilationUnit();
        if (!method.isPrivate() || unit.isEmpty()) {
            return false;
        }
        String name = method.getNameAsString();
        List<Expression> uses = new ArrayList<>();
        for (MethodCallExpr call : unit.get().findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals(name) && call.getArguments().size() == method.getParameters().size()) {
                uses.add(call);
            }
        }
        for (MethodReferenceExpr reference : unit.get().findAll(MethodReferenceExpr.class)) {
            if (reference.getIdentifier().equals(name)) {
                uses.add(reference);
            }
        }
        return !uses.isEmpty() && uses.stream().allMatch(this::isResultCheckedForNull);
    }

    private boolean isResultCheckedForNull(Expression use) {
        Node parent = outerOf(use);
        if (use.isMethodReferenceExpr()) {
            // .map(Meta::sanitize).filter(Objects::nonNull)
            return parent instanceof MethodCallExpr mapping && outerOf(mapping) instanceof MethodCallExpr next
                    && next.getScope().filter(scope -> Nodes.unwrap(scope) == mapping).isPresent()
                    && FILTER.equals(next.getNameAsString())
                    && NOT_NULL_FILTER.matcher(next.getArguments().toString()).find();
        }
        if (isNullCheck(parent, use)) {
            return true;
        }
        Optional<String> variable = Optional.empty();
        if (parent instanceof VariableDeclarator declarator) {
            variable = Optional.of(declarator.getNameAsString());
        } else if (parent instanceof AssignExpr assignment && assignment.getTarget().isNameExpr()) {
            variable = Optional.of(assignment.getTarget().asNameExpr().getNameAsString());
        }
        Node owner = use.findAncestor(MethodDeclaration.class).map(Node.class::cast).orElse(null);
        if (owner == null) {
            owner = use.findAncestor(ConstructorDeclaration.class).map(Node.class::cast).orElse(null);
        }
        if (variable.isEmpty() || owner == null) {
            return false;
        }
        String checked = variable.get();
        return owner.findAll(NameExpr.class).stream()
                .filter(mention -> mention.getNameAsString().equals(checked))
                .anyMatch(mention -> isNullCheck(outerOf(mention), mention));
    }

    // value == null, Objects.nonNull(value), Optional.ofNullable(value), CollectionUtils.isEmpty(value)
    private boolean isNullCheck(Node parent, Expression value) {
        if (parent instanceof BinaryExpr comparison) {
            boolean isEquality = comparison.getOperator() == BinaryExpr.Operator.EQUALS
                    || comparison.getOperator() == BinaryExpr.Operator.NOT_EQUALS;
            boolean withNull = comparison.getLeft().isNullLiteralExpr() || comparison.getRight().isNullLiteralExpr();
            return isEquality && withNull;
        }
        if (!(parent instanceof MethodCallExpr check)) {
            return false;
        }
        boolean isArgument = check.getArguments().stream().anyMatch(argument -> Nodes.unwrap(argument) == value);
        String method = check.getNameAsString();
        return isArgument && (FlowLibrary.isTrueForNull(method) || FlowLibrary.isFalseForNull(method)
                || NULL_HANDLERS.matcher(method).matches());
    }

    // Ближайший узел, который не скобки и не приведение типа
    private Node outerOf(Expression expression) {
        Node parent = expression.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent;
    }

    // if (source == null) return null: на пустой ввод - пустой результат. Кто передал объект, null не получит
    private boolean isNullPassThrough(ReturnStmt returned, MethodDeclaration method) {
        Set<String> parameters = method.getParameters().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.toSet());
        // if (...) return null; либо if (...) { return null; }
        Optional<Node> branch = returned.getParentNode()
                .filter(parent -> parent instanceof BlockStmt block && block.getStatements().size() == 1)
                .flatMap(Node::getParentNode)
                .or(returned::getParentNode);
        return branch
                .filter(parent -> parent instanceof IfStmt)
                .map(parent -> (IfStmt) parent)
                .filter(ifStmt -> ifStmt.getThenStmt() == returned || ifStmt.getThenStmt().getChildNodes().contains(returned))
                .filter(ifStmt -> isParameterNullCheck(ifStmt.getCondition(), parameters))
                .isPresent();
    }

    // source == null, а также a == null || b == null по параметрам
    private boolean isParameterNullCheck(Expression condition, Set<String> parameters) {
        Expression value = Nodes.unwrap(condition);
        if (!value.isBinaryExpr()) {
            return false;
        }
        BinaryExpr binary = value.asBinaryExpr();
        if (binary.getOperator() == BinaryExpr.Operator.OR) {
            return isParameterNullCheck(binary.getLeft(), parameters) && isParameterNullCheck(binary.getRight(), parameters);
        }
        return binary.getOperator() == BinaryExpr.Operator.EQUALS
                && (isParameter(binary.getLeft(), parameters) && binary.getRight().isNullLiteralExpr()
                || isParameter(binary.getRight(), parameters) && binary.getLeft().isNullLiteralExpr());
    }

    private boolean isParameter(Expression expression, Set<String> parameters) {
        return expression.isNameExpr() && parameters.contains(expression.asNameExpr().getNameAsString());
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
