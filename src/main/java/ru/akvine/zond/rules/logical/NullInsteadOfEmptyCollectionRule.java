package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
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
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class NullInsteadOfEmptyCollectionRule extends AbstractRule {
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "List", "Set", "Map", "Collection", "Iterable", "Queue", "Deque", "SortedSet", "SortedMap", "Stream");
    private static final String ARRAY_SUFFIX = "[]";
    private static final String NULLABLE = "Nullable";

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
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            String type = LocalTypes.typeName(method.getType());
            boolean returnsCollection = COLLECTION_TYPES.contains(type) || type.endsWith(ARRAY_SUFFIX);
            if (!returnsCollection || Annotations.has(method, NULLABLE) || TestClasses.isInside(method)) {
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
