package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Защищено ли использование значения проверкой, которая стоит на пути выполнения к нему.
 * <p>
 * Проверка где-нибудь в методе защитой не считается: она должна либо охватывать использование
 * (условие if, цикла, тернарного оператора, левая часть && и ||), либо стоять выше и обрывать выполнение
 * (if (list.isEmpty()) return;). Смысл условия не разбирается: проверка isEmpty() и проверка !isEmpty()
 * считаются защитой одинаково - ошибку в самом условии этот анализ не найдет.
 */
@UtilityClass
class Guards {
    // Objects.nonNull(x), Objects.requireNonNull(x), StringUtils.hasText(x), Assert.notNull(x, ...)
    private static final Set<String> NULL_CHECK_METHODS = Set.of(
            "nonNull", "isNull", "requireNonNull", "notNull", "hasText", "hasLength", "isEmpty", "isNotEmpty",
            "isBlank", "isNotBlank", "ofNullable");

    /**
     * @param usage   место, где значение используется без права на ошибку: list.get(0), optional.get()
     * @param isCheck распознает проверку этого значения: list.isEmpty(), optional.isPresent()
     */
    boolean isGuarded(Node usage, Predicate<Expression> isCheck) {
        Node child = usage;
        Node parent = usage.getParentNode().orElse(null);
        Node boundary = Nodes.enclosingCallable(usage).orElse(null);
        while (parent != null && child != boundary) {
            if (isGuardedBy(parent, child, isCheck)) {
                return true;
            }
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * @return true, если выражение проверяет переменную на null: x == null, x != null, Objects.nonNull(x),
     * StringUtils.hasText(x) и подобные
     */
    boolean isNullCheck(Expression expression, String variable) {
        if (expression.isBinaryExpr()) {
            BinaryExpr comparison = expression.asBinaryExpr();
            boolean isEquality = comparison.getOperator() == BinaryExpr.Operator.EQUALS
                    || comparison.getOperator() == BinaryExpr.Operator.NOT_EQUALS;
            return isEquality && (isVariableAndNull(comparison.getLeft(), comparison.getRight(), variable)
                    || isVariableAndNull(comparison.getRight(), comparison.getLeft(), variable));
        }
        return expression.isMethodCallExpr()
                && NULL_CHECK_METHODS.contains(expression.asMethodCallExpr().getNameAsString())
                && expression.asMethodCallExpr().getArguments().stream()
                .anyMatch(argument -> argument.toString().equals(variable));
    }

    private boolean isVariableAndNull(Expression first, Expression second, String variable) {
        return first.toString().equals(variable) && second.isNullLiteralExpr();
    }

    // child - часть parent, через которую мы поднялись от использования
    private boolean isGuardedBy(Node parent, Node child, Predicate<Expression> isCheck) {
        if (parent instanceof IfStmt branch) {
            return child != branch.getCondition() && hasCheck(branch.getCondition(), isCheck);
        }
        if (parent instanceof ConditionalExpr ternary) {
            return child != ternary.getCondition() && hasCheck(ternary.getCondition(), isCheck);
        }
        // list.isEmpty() || list.get(0) == null: правая часть вычисляется только после левой
        if (parent instanceof BinaryExpr binary && isShortCircuit(binary)) {
            return child == binary.getRight() && hasCheck(binary.getLeft(), isCheck);
        }
        if (parent instanceof WhileStmt loop) {
            return child != loop.getCondition() && hasCheck(loop.getCondition(), isCheck);
        }
        if (parent instanceof ForStmt loop) {
            return loop.getCompare().filter(compare -> child != compare && hasCheck(compare, isCheck)).isPresent();
        }
        // switch (list.size()) { case 1 -> list.get(0); }
        if (parent instanceof SwitchEntry entry) {
            return entry.getParentNode().filter(owner -> hasCheck(selector(owner), isCheck)).isPresent()
                    || exitsBefore(entry.getStatements(), child, isCheck);
        }
        if (parent instanceof BlockStmt block) {
            return exitsBefore(block.getStatements(), child, isCheck);
        }
        // stream.filter(list -> !list.isEmpty()).map(list -> list.get(0)): проверка - в операции выше по цепочке
        if (parent instanceof LambdaExpr lambda) {
            return isCheckedEarlierInChain(lambda, isCheck);
        }
        return false;
    }

    // Выше по блоку стоит проверка, после которой выполнение дальше не идет: if (list.isEmpty()) return;
    private boolean exitsBefore(List<Statement> statements, Node child, Predicate<Expression> isCheck) {
        for (Statement statement : statements) {
            if (statement == child) {
                return false;
            }
            if (statement instanceof AssertStmt assertion && hasCheck(assertion.getCheck(), isCheck)) {
                return true;
            }
            if (statement instanceof IfStmt branch
                    && hasCheck(branch.getCondition(), isCheck)
                    && (alwaysExits(branch.getThenStmt()) || branch.getElseStmt().filter(Guards::alwaysExits).isPresent())) {
                return true;
            }
        }
        return false;
    }

    private boolean alwaysExits(Statement statement) {
        if (statement instanceof BlockStmt block) {
            return !block.getStatements().isEmpty() && alwaysExits(block.getStatements().getLast().get());
        }
        return statement instanceof ReturnStmt
                || statement instanceof ThrowStmt
                || statement instanceof ContinueStmt
                || statement instanceof BreakStmt;
    }

    private boolean isCheckedEarlierInChain(LambdaExpr lambda, Predicate<Expression> isCheck) {
        Optional<Expression> earlier = lambda.getParentNode()
                .filter(operation -> operation instanceof MethodCallExpr)
                .flatMap(operation -> ((MethodCallExpr) operation).getScope());
        while (earlier.isPresent() && earlier.get().isMethodCallExpr()) {
            MethodCallExpr operation = earlier.get().asMethodCallExpr();
            if (operation.getArguments().stream().anyMatch(argument -> hasCheck(argument, isCheck))) {
                return true;
            }
            earlier = operation.getScope();
        }
        return false;
    }

    private Expression selector(Node switchNode) {
        if (switchNode instanceof SwitchStmt statement) {
            return statement.getSelector();
        }
        return switchNode instanceof SwitchExpr expression ? expression.getSelector() : null;
    }

    // Проверка стоит прямо в условии либо сохранена в переменную: boolean empty = list.isEmpty(); if (empty) ...
    private boolean hasCheck(Expression condition, Predicate<Expression> isCheck) {
        if (condition == null) {
            return false;
        }
        for (Expression part : condition.findAll(Expression.class)) {
            if (isCheck.test(part)) {
                return true;
            }
            boolean isCheckedVariable = part.isNameExpr() && LocalTypes.findInitializer(part)
                    .filter(initializer -> initializer.findAll(Expression.class).stream().anyMatch(isCheck))
                    .isPresent();
            if (isCheckedVariable) {
                return true;
            }
        }
        return false;
    }

    private boolean isShortCircuit(BinaryExpr binary) {
        return binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR;
    }
}
