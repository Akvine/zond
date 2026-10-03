package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckCompareBySubtractionRule extends AbstractRule {
    private static final String COMPARATOR = "Comparator";
    private static final Set<String> COMPARE_METHODS = Set.of("compareTo", "compare");

    // Места, куда лямбда с двумя параметрами передается именно как компаратор
    private static final Set<String> SORTING_METHODS = Set.of("sort", "sorted", "min", "max", "thenComparing");
    private static final Set<String> SORTED_COLLECTIONS = Set.of("TreeSet", "TreeMap", "PriorityQueue");

    @Override
    public String code() {
        return RuleCodes.CHECK_COMPARE_BY_SUBTRACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет compareTo и Comparator, реализованные через вычитание";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (!COMPARE_METHODS.contains(method.getNameAsString())) {
                continue;
            }
            for (ReturnStmt returnStmt : method.findAll(ReturnStmt.class)) {
                boolean subtraction = returnStmt.getExpression().filter(this::isSubtraction).isPresent();
                if (subtraction && !Nodes.isInNestedScope(returnStmt, method)) {
                    violations.add(report(sourceFile, returnStmt, returnStmt.getExpression().get()));
                }
            }
        }

        for (LambdaExpr lambda : sourceFile.unit().findAll(LambdaExpr.class)) {
            if (lambda.getParameters().size() != 2 || !isComparator(lambda)) {
                continue;
            }
            lambda.getExpressionBody()
                    .filter(this::isSubtraction)
                    .ifPresent(body -> violations.add(report(sourceFile, lambda, body)));
            for (ReturnStmt returnStmt : lambda.getBody().findAll(ReturnStmt.class)) {
                returnStmt.getExpression()
                        .filter(this::isSubtraction)
                        .ifPresent(value -> violations.add(report(sourceFile, returnStmt, value)));
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

    private Violation report(SourceFile sourceFile, Node node, Expression subtraction) {
        return violation(sourceFile, node,
                "Сравнение через вычитание '" + subtraction + "': при больших по модулю значениях разность"
                        + " переполняется и знак результата меняется; используйте Integer.compare(...),"
                        + " Long.compare(...) или Comparator.comparing(...)");
    }

    // a - b, (int) (a - b)
    private boolean isSubtraction(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isCastExpr()) {
            return isSubtraction(value.asCastExpr().getExpression());
        }
        return value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.MINUS;
    }

    private boolean isComparator(LambdaExpr lambda) {
        Node parent = lambda.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr call) {
            return SORTING_METHODS.contains(call.getNameAsString());
        }
        if (parent instanceof ObjectCreationExpr creation) {
            return SORTED_COLLECTIONS.contains(creation.getType().getNameAsString());
        }
        return parent instanceof VariableDeclarator variable
                && COMPARATOR.equals(LocalTypes.typeName(variable.getType()));
    }
}
