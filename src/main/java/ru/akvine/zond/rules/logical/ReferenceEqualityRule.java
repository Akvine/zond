package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ReferenceEqualityRule extends AbstractRule {
    private static final String STRING = "String";
    // Boolean сюда не входит: упаковка всегда дает Boolean.TRUE либо Boolean.FALSE, и ссылки совпадают
    private static final Set<String> BOXED_TYPES = Set.of("Integer", "Long", "Short", "Byte", "Character", "Double", "Float");
    private static final String INTERN = "intern";
    private static final String EQUALS = "equals";
    private static final String OBJECTS = "Objects";
    private static final Set<String> STORING = Set.of("put", "putIfAbsent", "add", "set", "offer", "push");
    private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Set<BinaryExpr.Operator> ARITHMETIC = Set.of(
            BinaryExpr.Operator.PLUS, BinaryExpr.Operator.MINUS, BinaryExpr.Operator.MULTIPLY, BinaryExpr.Operator.DIVIDE,
            BinaryExpr.Operator.REMAINDER, BinaryExpr.Operator.BINARY_AND, BinaryExpr.Operator.BINARY_OR,
            BinaryExpr.Operator.XOR, BinaryExpr.Operator.LEFT_SHIFT, BinaryExpr.Operator.SIGNED_RIGHT_SHIFT,
            BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT);

    @Override
    public String code() {
        return RuleCodes.REFERENCE_EQUALITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сравнение строк и оберток (Integer, Long и т.п.) через == вместо equals";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr comparison : sourceFile.unit().findAll(BinaryExpr.class)) {
            if (comparison.getOperator() != BinaryExpr.Operator.EQUALS
                    && comparison.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
                continue;
            }

            Expression left = Nodes.unwrap(comparison.getLeft());
            Expression right = Nodes.unwrap(comparison.getRight());
            // Сравнение ссылок задумано: assert v == TIMESTAMP_FIELD_NAME, name == name.intern(), key != normalized
            if (left.isNullLiteralExpr() || right.isNullLiteralExpr() || isIntendedIdentity(comparison, left, right)) {
                continue;
            }

            Optional<String> leftType = LocalTypes.typeOf(left);
            Optional<String> rightType = LocalTypes.typeOf(right);
            String operator = comparison.getOperator().asString();

            if (leftType.filter(STRING::equals).isPresent() || rightType.filter(STRING::equals).isPresent()) {
                violations.add(violation(sourceFile, comparison,
                        "Сравнение строк через " + operator + " в '" + comparison + "': сравниваются ссылки,"
                                + " а не содержимое; используйте equals"));
            } else if (leftType.filter(BOXED_TYPES::contains).isPresent()
                    && rightType.filter(BOXED_TYPES::contains).isPresent()
                    && !isPrimitiveValue(left) && !isPrimitiveValue(right)) {
                // Обертка против примитива распаковывается и сравнивается по значению, поэтому нужны обе обертки
                violations.add(violation(sourceFile, comparison,
                        "Сравнение оберток " + leftType.get() + " через " + operator + " в '" + comparison
                                + "': сравниваются ссылки, вне кэша значений результат будет false;"
                                + " используйте equals"));
            }
        }
        return violations;
    }

    private boolean isIntendedIdentity(BinaryExpr comparison, Expression left, Expression right) {
        return comparison.findAncestor(AssertStmt.class).isPresent()
                || comparison.findAll(MethodCallExpr.class).stream().anyMatch(call -> INTERN.equals(call.getNameAsString()))
                || isSameInstanceCheck(left, right) || isSameInstanceCheck(right, left)
                || isFastPathBeforeEquals(comparison, left, right)
                || isSentinel(comparison, left) || isSentinel(comparison, right);
    }

    // a == b || a.equals(b), if (s1 == s2) return true; ... s1.equals(s2): одна и та же ссылка - быстрый ответ,
    // а содержимое сравнивает equals рядом
    private boolean isFastPathBeforeEquals(BinaryExpr comparison, Expression left, Expression right) {
        Optional<Node> scope = comparison.getParentNode()
                .filter(parent -> parent instanceof BinaryExpr either && either.getOperator() == BinaryExpr.Operator.OR)
                .or(() -> comparison.getParentNode()
                        .filter(parent -> parent instanceof IfStmt branch && branch.getCondition() == comparison
                                && returnsTrue(branch.getThenStmt()))
                        .flatMap(branch -> Nodes.enclosingCallable(comparison)));
        Set<String> operands = Set.of(left.toString(), right.toString());
        return scope.stream()
                .flatMap(node -> node.findAll(MethodCallExpr.class).stream())
                .filter(call -> EQUALS.equals(call.getNameAsString()))
                .anyMatch(call -> call.getScope().map(Nodes::unwrap).map(Expression::toString)
                        .filter(operands::contains).isPresent()
                        && call.getArguments().stream().map(Nodes::unwrap).map(Expression::toString)
                        .anyMatch(operands::contains)
                        || call.getScope().filter(owner -> OBJECTS.equals(owner.toString())).isPresent()
                        && call.getArguments().stream().map(Nodes::unwrap).map(Expression::toString)
                        .allMatch(operands::contains));
    }

    private boolean returnsTrue(Statement statement) {
        Statement single = statement instanceof BlockStmt block && block.getStatements().size() == 1
                ? block.getStatement(0)
                : statement;
        return single instanceof ReturnStmt returned && returned.getExpression()
                .filter(value -> value.isBooleanLiteralExpr() && value.asBooleanLiteralExpr().getValue())
                .isPresent();
    }

    // namespace == UNKNOWN_NAMESPACE, где сам класс кладет UNKNOWN_NAMESPACE как значение (namespaces.put(prefix,
    // UNKNOWN_NAMESPACE)): константа-метка, и сравнивают именно ее экземпляр
    private boolean isSentinel(BinaryExpr comparison, Expression side) {
        if (!side.isNameExpr() || !CONSTANT_NAME.matcher(side.asNameExpr().getNameAsString()).matches()) {
            return false;
        }
        String name = side.asNameExpr().getNameAsString();
        return comparison.findAncestor(TypeDeclaration.class).stream()
                .flatMap(type -> ((Node) type).findAll(NameExpr.class).stream())
                .filter(usage -> usage != side && usage.getNameAsString().equals(name))
                .anyMatch(usage -> usage.getParentNode()
                        .filter(parent -> parent instanceof MethodCallExpr call && STORING.contains(call.getNameAsString())
                                && call.getArguments().stream().anyMatch(argument -> argument == usage)
                                || parent instanceof AssignExpr assignment && assignment.getValue() == usage
                                || parent instanceof ReturnStmt)
                        .isPresent());
    }

    /**
     * String normalizedKey = normalizeKeyPrefix(entry.getKey(), prefix); if (normalizedKey != entry.getKey()):
     * метод возвращает тот же объект, если менять нечего, и сравнение ссылок отвечает "изменилось ли"
     */
    private boolean isSameInstanceCheck(Expression result, Expression original) {
        return result.isNameExpr() && LocalTypes.findInitializer(result)
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .filter(initializer -> initializer.asMethodCallExpr().getArguments().stream()
                        .anyMatch(argument -> argument.toString().equals(original.toString())))
                .isPresent();
    }

    // total == failed + successful: арифметика распаковывает обертки, и сравниваются числа
    private boolean isPrimitiveValue(Expression expression) {
        return expression.isLiteralExpr() || expression.isUnaryExpr()
                || expression.isBinaryExpr() && ARITHMETIC.contains(expression.asBinaryExpr().getOperator());
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
