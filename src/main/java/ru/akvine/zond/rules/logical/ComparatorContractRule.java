package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * (a, b) -> a.getPrice() > b.getPrice() ? 1 : -1: такой компаратор никогда не возвращает 0, и равные элементы
 * сравниваются "больше" в обе стороны. Сортировка большого списка падает с "Comparison method violates its
 * general contract!", TreeSet и TreeMap перестают находить уже лежащие в них элементы
 */
@Component
public class ComparatorContractRule extends AbstractRule {
    private static final String COMPARE = "compare";
    private static final String COMPARE_TO = "compareTo";
    private static final String INT = "int";
    // Куда передают компаратор-лямбду
    private static final Set<String> COMPARATOR_CONSUMERS = Set.of(
            "sort", "sorted", "max", "min", "thenComparing", "parallelSort", "binarySearch", "maxBy", "minBy");
    private static final Set<String> SORTED_COLLECTIONS = Set.of(
            "TreeSet", "TreeMap", "PriorityQueue", "ConcurrentSkipListSet", "ConcurrentSkipListMap",
            "PriorityBlockingQueue");

    @Override
    public String code() {
        return RuleCodes.COMPARATOR_CONTRACT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет компараторы, которые никогда не возвращают 0: a > b ? 1 : -1";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (isComparisonMethod(method) && method.getBody().isPresent()) {
                List<Expression> results = method.getBody().get().findAll(ReturnStmt.class).stream()
                        .filter(statement -> !Nodes.isInNestedScope(statement, method))
                        .flatMap(statement -> statement.getExpression().stream())
                        .toList();
                report(sourceFile, method, results, violations);
            }
        }
        for (LambdaExpr lambda : sourceFile.unit().findAll(LambdaExpr.class)) {
            if (lambda.getParameters().size() == 2 && isComparatorLambda(lambda)) {
                List<Expression> results = lambda.getExpressionBody().map(List::of)
                        .orElseGet(() -> lambda.getBody().findAll(ReturnStmt.class).stream()
                                .filter(statement -> !Nodes.isInNestedScope(statement, lambda))
                                .flatMap(statement -> statement.getExpression().stream())
                                .toList());
                report(sourceFile, lambda, results, violations);
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

    private void report(SourceFile sourceFile, Node comparator, List<Expression> results, List<Violation> violations) {
        // Нужен хотя бы один тернарный оператор: return 1; в ветке без него - обычный разбор случаев
        boolean ternary = results.stream().anyMatch(result -> Nodes.unwrap(result) instanceof ConditionalExpr);
        if (!results.isEmpty() && ternary && results.stream().allMatch(this::isNeverZero)) {
            violations.add(violation(sourceFile, comparator,
                    "Компаратор никогда не возвращает 0: равные элементы получаются \"больше\" друг друга в обе"
                            + " стороны, и сортировка падает с \"Comparison method violates its general contract!\","
                            + " а TreeSet и TreeMap теряют элементы; сравнивайте через Integer.compare(a, b)"
                            + " или Comparator.comparing(...)"));
        }
    }

    // 1, -1 и тернарный оператор, все ветки которого - такие же ненулевые числа
    private boolean isNeverZero(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value instanceof ConditionalExpr ternary) {
            return isNeverZero(ternary.getThenExpr()) && isNeverZero(ternary.getElseExpr());
        }
        if (value instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS) {
            return isNeverZero(unary.getExpression());
        }
        return value.isIntegerLiteralExpr() && literal(value).filter(number -> number != 0).isPresent();
    }

    private Optional<Long> literal(Expression value) {
        try {
            return Optional.of(value.asIntegerLiteralExpr().asNumber().longValue());
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    // compare(a, b) у Comparator и compareTo(other) у Comparable
    private boolean isComparisonMethod(MethodDeclaration method) {
        if (!INT.equals(method.getType().asString())) {
            return false;
        }
        String name = method.getNameAsString();
        return COMPARE.equals(name) && method.getParameters().size() == 2
                || COMPARE_TO.equals(name) && method.getParameters().size() == 1;
    }

    // list.sort((a, b) -> ...), new TreeSet<>((a, b) -> ...), Comparator<User> byAge = (a, b) -> ...
    private boolean isComparatorLambda(LambdaExpr lambda) {
        Optional<Node> parent = lambda.getParentNode();
        if (parent.isEmpty()) {
            return false;
        }
        if (parent.get() instanceof MethodCallExpr call) {
            return COMPARATOR_CONSUMERS.contains(call.getNameAsString());
        }
        if (parent.get() instanceof ObjectCreationExpr creation) {
            return SORTED_COLLECTIONS.contains(creation.getType().getNameAsString());
        }
        return parent.get() instanceof VariableDeclarator variable
                && variable.getType().asString().startsWith("Comparator");
    }
}
