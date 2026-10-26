package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Значения, известные уже при чтении кода: литералы, арифметика над ними, константа и локальная переменная с
 * таким начальным значением, которой больше ничего не присваивают. По ним видно, какая ветка if, switch и ?:
 * не выполнится никогда: (7 * 18) + num > 200 ? "safe" : param при int num = 106 всегда дает "safe".
 * <p>
 * Целые числа и символы считаются как long, дробные - как double, строки и логические значения - как есть.
 */
@UtilityClass
public class ConstantValues {
    // Через сколько переменных и вложенных выражений прослеживается значение
    private static final int MAX_DEPTH = 8;
    private static final Set<UnaryExpr.Operator> CHANGES = Set.of(UnaryExpr.Operator.PREFIX_INCREMENT,
            UnaryExpr.Operator.PREFIX_DECREMENT, UnaryExpr.Operator.POSTFIX_INCREMENT,
            UnaryExpr.Operator.POSTFIX_DECREMENT);

    /**
     * @return значение выражения, если оно известно без запуска кода
     */
    public Optional<Object> evaluate(Expression expression) {
        return evaluate(expression, 0);
    }

    /**
     * @return значение условия, если оно известно без запуска кода
     */
    public Optional<Boolean> condition(Expression expression) {
        return evaluate(expression).filter(Boolean.class::isInstance).map(Boolean.class::cast);
    }

    /**
     * @return true, если узел стоит в ветке, которая не выполнится никогда: в ветке if или ?: против известного
     * условия либо в case, до которого switch по известному значению не дойдет
     */
    public boolean isUnreachable(Node node, Node boundary) {
        Node child = node;
        Node parent = node.getParentNode().orElse(null);
        while (parent != null && child != boundary) {
            if (parent instanceof IfStmt branch && branch.getCondition() != child) {
                Optional<Boolean> known = condition(branch.getCondition());
                if (known.isPresent() && known.get() != (branch.getThenStmt() == child)) {
                    return true;
                }
            }
            if (parent instanceof ConditionalExpr branch && branch.getCondition() != child) {
                Optional<Boolean> known = condition(branch.getCondition());
                if (known.isPresent() && known.get() != (branch.getThenExpr() == child)) {
                    return true;
                }
            }
            if (parent instanceof SwitchEntry entry && entry.getParentNode().orElse(null) instanceof SwitchStmt choice
                    && containsStatement(entry, child) && isSkipped(choice, entry)) {
                return true;
            }
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * @return true, если оба значения известны и равны; числа и символы сравниваются как числа
     */
    public boolean same(Object left, Object right) {
        if (isNumeric(left) && isNumeric(right)) {
            return number(left).doubleValue() == number(right).doubleValue();
        }
        return left.equals(right);
    }

    private boolean containsStatement(SwitchEntry entry, Node child) {
        return entry.getStatements().stream().anyMatch(statement -> statement == child);
    }

    private boolean isSkipped(SwitchStmt choice, SwitchEntry entry) {
        return liveEntries(choice).filter(reached -> reached.stream().noneMatch(found -> found == entry)).isPresent();
    }

    /**
     * switch (selector) с известным значением: case, с которого начнется выполнение, и те, куда оно провалится.
     *
     * @return пусто, если значение или метки неизвестны (case RED у enum) и выполниться может любая ветка
     */
    public Optional<List<SwitchEntry>> liveEntries(SwitchStmt choice) {
        Optional<Object> selector = evaluate(choice.getSelector());
        if (selector.isEmpty()) {
            return Optional.empty();
        }
        List<SwitchEntry> entries = choice.getEntries();
        int start = -1;
        for (int index = 0; index < entries.size() && start < 0; index++) {
            for (Expression label : entries.get(index).getLabels()) {
                Optional<Object> value = evaluate(label);
                if (value.isEmpty()) {
                    return Optional.empty();
                }
                if (same(value.get(), selector.get())) {
                    start = index;
                }
            }
        }
        if (start < 0) {
            start = indexOfDefault(entries);
        }
        List<SwitchEntry> reached = new ArrayList<>();
        for (int index = Math.max(start, 0); start >= 0 && index < entries.size(); index++) {
            SwitchEntry current = entries.get(index);
            reached.add(current);
            if (current.getType() != SwitchEntry.Type.STATEMENT_GROUP || endsWithExit(current)) {
                break;
            }
        }
        return Optional.of(reached);
    }

    private int indexOfDefault(List<SwitchEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).getLabels().isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    private boolean endsWithExit(SwitchEntry entry) {
        if (entry.getStatements().isEmpty()) {
            return false;
        }
        Statement last = entry.getStatements().get(entry.getStatements().size() - 1);
        return last.isBreakStmt() || last.isReturnStmt() || last.isThrowStmt() || last.isContinueStmt();
    }

    private Optional<Object> evaluate(Expression expression, int depth) {
        if (depth > MAX_DEPTH) {
            return Optional.empty();
        }
        Expression value = Nodes.unwrap(expression);
        try {
            if (value.isIntegerLiteralExpr()) {
                return Optional.of(value.asIntegerLiteralExpr().asNumber().longValue());
            }
            if (value.isLongLiteralExpr()) {
                return Optional.of(value.asLongLiteralExpr().asNumber().longValue());
            }
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        if (value.isDoubleLiteralExpr()) {
            return Optional.of(value.asDoubleLiteralExpr().asDouble());
        }
        if (value.isCharLiteralExpr()) {
            return Optional.of((long) value.asCharLiteralExpr().asChar());
        }
        if (value.isBooleanLiteralExpr()) {
            return Optional.of(value.asBooleanLiteralExpr().getValue());
        }
        if (value.isStringLiteralExpr()) {
            return Optional.of(value.asStringLiteralExpr().asString());
        }
        if (value.isUnaryExpr()) {
            return unary(value.asUnaryExpr(), depth);
        }
        if (value.isBinaryExpr()) {
            return binary(value.asBinaryExpr(), depth);
        }
        if (value.isConditionalExpr()) {
            ConditionalExpr branch = value.asConditionalExpr();
            return evaluate(branch.getCondition(), depth + 1)
                    .filter(Boolean.class::isInstance)
                    .flatMap(known -> evaluate((Boolean) known ? branch.getThenExpr() : branch.getElseExpr(), depth + 1));
        }
        if (value.isNameExpr()) {
            return variable(value.asNameExpr(), depth);
        }
        if (value.isMethodCallExpr()) {
            return stringMethod(value.asMethodCallExpr(), depth);
        }
        return Optional.empty();
    }

    private Optional<Object> unary(UnaryExpr unary, int depth) {
        Optional<Object> operand = evaluate(unary.getExpression(), depth + 1);
        if (operand.isEmpty()) {
            return Optional.empty();
        }
        Object known = operand.get();
        return switch (unary.getOperator()) {
            case LOGICAL_COMPLEMENT -> known instanceof Boolean flag ? Optional.of(!flag) : Optional.empty();
            case MINUS -> known instanceof Double fraction ? Optional.of(-fraction)
                    : known instanceof Long whole ? Optional.of(-whole) : Optional.empty();
            case PLUS -> isNumeric(known) ? Optional.of(known) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private Optional<Object> binary(BinaryExpr binary, int depth) {
        Optional<Object> left = evaluate(binary.getLeft(), depth + 1);
        // false && x и true || x известны и без правой части
        if (binary.getOperator() == BinaryExpr.Operator.AND && left.filter(Boolean.FALSE::equals).isPresent()
                || binary.getOperator() == BinaryExpr.Operator.OR && left.filter(Boolean.TRUE::equals).isPresent()) {
            return left;
        }
        Optional<Object> right = evaluate(binary.getRight(), depth + 1);
        if (left.isEmpty() || right.isEmpty()) {
            return Optional.empty();
        }
        Object a = left.get();
        Object b = right.get();
        if (binary.getOperator() == BinaryExpr.Operator.PLUS && (a instanceof String || b instanceof String)) {
            return Optional.of(String.valueOf(a) + b);
        }
        if (a instanceof Boolean first && b instanceof Boolean second) {
            return switch (binary.getOperator()) {
                case AND -> Optional.of(first && second);
                case OR -> Optional.of(first || second);
                case EQUALS -> Optional.of(first.equals(second));
                case NOT_EQUALS -> Optional.of(!first.equals(second));
                default -> Optional.empty();
            };
        }
        if (!isNumeric(a) || !isNumeric(b)) {
            return Optional.empty();
        }
        return a instanceof Double || b instanceof Double
                ? fractional(binary.getOperator(), number(a).doubleValue(), number(b).doubleValue())
                : whole(binary.getOperator(), number(a).longValue(), number(b).longValue());
    }

    private Optional<Object> whole(BinaryExpr.Operator operator, long a, long b) {
        return switch (operator) {
            case PLUS -> Optional.of(a + b);
            case MINUS -> Optional.of(a - b);
            case MULTIPLY -> Optional.of(a * b);
            case DIVIDE -> b == 0 ? Optional.empty() : Optional.of(a / b);
            case REMAINDER -> b == 0 ? Optional.empty() : Optional.of(a % b);
            case LESS -> Optional.of(a < b);
            case LESS_EQUALS -> Optional.of(a <= b);
            case GREATER -> Optional.of(a > b);
            case GREATER_EQUALS -> Optional.of(a >= b);
            case EQUALS -> Optional.of(a == b);
            case NOT_EQUALS -> Optional.of(a != b);
            default -> Optional.empty();
        };
    }

    private Optional<Object> fractional(BinaryExpr.Operator operator, double a, double b) {
        return switch (operator) {
            case PLUS -> Optional.of(a + b);
            case MINUS -> Optional.of(a - b);
            case MULTIPLY -> Optional.of(a * b);
            case DIVIDE -> Optional.of(a / b);
            case LESS -> Optional.of(a < b);
            case LESS_EQUALS -> Optional.of(a <= b);
            case GREATER -> Optional.of(a > b);
            case GREATER_EQUALS -> Optional.of(a >= b);
            default -> Optional.empty();
        };
    }

    // final-константа с начальным значением либо локальная переменная, которой больше ничего не присваивают
    private Optional<Object> variable(NameExpr name, int depth) {
        Optional<Node> declaration = LocalTypes.findDeclaration(name);
        if (declaration.isEmpty() || !(declaration.get() instanceof VariableDeclarator variable)
                || variable.getInitializer().isEmpty()) {
            return Optional.empty();
        }
        Optional<FieldDeclaration> field = variable.findAncestor(FieldDeclaration.class);
        if (field.isPresent()) {
            return field.get().isFinal() ? evaluate(variable.getInitializer().get(), depth + 1) : Optional.empty();
        }
        Optional<Node> callable = Nodes.enclosingCallable(variable);
        boolean changed = callable.isEmpty() || callable.get().findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().isNameExpr()
                        && LocalTypes.findDeclaration(assignment.getTarget()).filter(found -> found == variable).isPresent())
                || callable.get().findAll(UnaryExpr.class).stream()
                .anyMatch(unary -> CHANGES.contains(unary.getOperator()) && unary.getExpression().isNameExpr()
                        && LocalTypes.findDeclaration(unary.getExpression()).filter(found -> found == variable).isPresent());
        return changed ? Optional.empty() : evaluate(variable.getInitializer().get(), depth + 1);
    }

    // "ABC".charAt(1), guess.length(), mode.equals("safe")
    private Optional<Object> stringMethod(MethodCallExpr call, int depth) {
        Optional<Object> scope = call.getScope().flatMap(found -> evaluate(found, depth + 1));
        if (scope.isEmpty() || !(scope.get() instanceof String text)) {
            return Optional.empty();
        }
        List<Optional<Object>> arguments = call.getArguments().stream()
                .map(argument -> evaluate(argument, depth + 1))
                .toList();
        if (arguments.stream().anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }
        String method = call.getNameAsString();
        if (arguments.isEmpty()) {
            return switch (method) {
                case "length" -> Optional.of((long) text.length());
                case "isEmpty" -> Optional.of(text.isEmpty());
                default -> Optional.empty();
            };
        }
        Object argument = arguments.get(0).get();
        if (arguments.size() == 1 && "charAt".equals(method) && argument instanceof Long index
                && index >= 0 && index < text.length()) {
            return Optional.of((long) text.charAt(index.intValue()));
        }
        if (arguments.size() == 1 && "equals".equals(method)) {
            return Optional.of(text.equals(argument));
        }
        return Optional.empty();
    }

    private boolean isNumeric(Object value) {
        return value instanceof Long || value instanceof Double;
    }

    private Number number(Object value) {
        return (Number) value;
    }
}
