package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.Set;

/**
 * Поиск элемента коллекции по ключу перебором: стрим с filter по равенству свойства либо цикл с таким же if.
 * Именно такой поиск заменяется на Map с мгновенным доступом по ключу.
 */
@UtilityClass
public class KeyLookups {
    private static final Set<String> STREAM_METHODS = Set.of("stream", "parallelStream");
    private static final String FILTER = "filter";
    private static final Set<String> MATCHERS = Set.of("anyMatch", "noneMatch");
    private static final Set<String> FINDERS = Set.of("findFirst", "findAny");
    private static final String EQUALS = "equals";
    private static final String ENUM_VALUES = "values";

    // Objects.equals(a, b) и его близнецы
    private static final Set<String> EQUALS_HELPERS = Set.of("Objects", "ObjectUtils", "StringUtils");
    private static final int HELPER_ARGUMENTS = 2;

    // Коллекция из перечисленных в коде значений заведомо мала: искать в ней перебором - не потеря
    private static final Set<String> FIXED_FACTORIES = Set.of("of", "asList", "singletonList", "singleton", "emptyList");
    private static final Set<String> FIXED_FACTORY_OWNERS = Set.of("List", "Set", "Arrays", "Collections", "ImmutableList");

    /**
     * Условие поиска: свойство элемента сравнивается со значением, которое от элемента не зависит
     *
     * @param property свойство элемента: customer.getId()
     * @param key      искомое значение: order.getCustomerId()
     */
    public record KeyMatch(Expression property, Expression key) {
    }

    /**
     * @param place      что показывать в находке: операция стрима либо цикл
     * @param collection коллекция, в которой ищут
     * @param findsOne   поиск останавливается на первом совпадении: findFirst, anyMatch, return или break в цикле
     */
    public record Lookup(Node place, Expression collection, KeyMatch match, boolean findsOne) {
    }

    /**
     * @param streamCall вызов stream() у коллекции
     * @return поиск по ключу, если сразу за stream() идет filter либо anyMatch с равенством свойства элемента
     */
    public Optional<Lookup> ofStream(MethodCallExpr streamCall) {
        if (!STREAM_METHODS.contains(streamCall.getNameAsString()) || !streamCall.getArguments().isEmpty()
                || streamCall.getScope().isEmpty()) {
            return Optional.empty();
        }
        Optional<MethodCallExpr> operation = nextInChain(streamCall)
                .filter(next -> FILTER.equals(next.getNameAsString()) || MATCHERS.contains(next.getNameAsString()))
                .filter(next -> next.getArguments().size() == 1 && next.getArgument(0).isLambdaExpr());
        if (operation.isEmpty()) {
            return Optional.empty();
        }
        LambdaExpr lambda = operation.get().getArgument(0).asLambdaExpr();
        if (lambda.getParameters().size() != 1) {
            return Optional.empty();
        }
        String element = lambda.getParameter(0).getNameAsString();
        return conditionOf(lambda)
                .flatMap(condition -> findKeyEquality(condition, element))
                .map(match -> new Lookup(
                        operation.get(), streamCall.getScope().get(), match, stopsAtFirst(operation.get())));
    }

    /**
     * @return поиск по ключу, если тело цикла - единственный if с равенством свойства элемента
     */
    public Optional<Lookup> ofLoop(ForEachStmt loop) {
        Expression iterable = Nodes.unwrap(loop.getIterable());
        boolean variable = iterable.isNameExpr()
                || iterable.isFieldAccessExpr() && iterable.asFieldAccessExpr().getScope().isThisExpr();
        if (!variable || loop.getVariable().getVariables().size() != 1) {
            return Optional.empty();
        }
        Statement body = loop.getBody();
        if (body.isBlockStmt() && body.asBlockStmt().getStatements().size() == 1) {
            body = body.asBlockStmt().getStatement(0);
        }
        // С else цикл обрабатывает и остальные элементы - это уже не поиск одного
        if (!body.isIfStmt() || body.asIfStmt().getElseStmt().isPresent()) {
            return Optional.empty();
        }
        IfStmt condition = body.asIfStmt();
        String element = loop.getVariable().getVariable(0).getNameAsString();
        boolean stops = !condition.getThenStmt().findAll(ReturnStmt.class).isEmpty()
                || !condition.getThenStmt().findAll(BreakStmt.class).isEmpty();
        return findKeyEquality(condition.getCondition(), element)
                .map(match -> new Lookup(loop, iterable, match, stops));
    }

    /**
     * Поиск повторяется на каждой итерации внешнего цикла: коллекция объявлена до него, а искомое значение
     * на каждой итерации свое. Вместе с циклом это O(N×M).
     */
    public boolean isRepeated(Lookup lookup) {
        Optional<Node> iteration = Loops.enclosingIteration(lookup.place());
        Optional<Node> declaration = LocalTypes.findDeclaration(lookup.collection());
        return iteration.isPresent() && declaration.isPresent()
                && !isShort(iteration.get())
                && Loops.isDeclaredOutside(iteration.get(), declaration.get())
                && !isFixed(declaration.get())
                && lookup.match().key().findAll(NameExpr.class).stream()
                .anyMatch(name -> LocalTypes.findDeclaration(name).filter(iteration.get()::isAncestorOf).isPresent());
    }

    // Перебор значений перечисления или значений, перечисленных в коде, короток: O(N×M) из него не выйдет
    private boolean isShort(Node iteration) {
        if (!(iteration instanceof ForEachStmt loop)) {
            return false;
        }
        Expression iterable = Nodes.unwrap(loop.getIterable());
        if (iterable.isMethodCallExpr()) {
            // Status.values() - перечисление; orders.values() - значения Map, их может быть сколько угодно
            MethodCallExpr call = iterable.asMethodCallExpr();
            boolean ofType = call.getScope()
                    .map(MethodCalls::receiverName)
                    .filter(owner -> !owner.isEmpty() && Character.isLowerCase(owner.charAt(0)))
                    .isEmpty();
            return ENUM_VALUES.equals(call.getNameAsString()) && call.getArguments().isEmpty() && ofType;
        }
        return LocalTypes.findDeclaration(iterable).filter(KeyLookups::isFixed).isPresent();
    }

    /**
     * @return true для коллекции, заданной перечислением значений прямо в коде: List.of(...), Arrays.asList(...)
     */
    public boolean isFixed(Node declaration) {
        if (!(declaration instanceof VariableDeclarator variable)) {
            return false;
        }
        return variable.getInitializer()
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> FIXED_FACTORIES.contains(call.getNameAsString()))
                .flatMap(MethodCallExpr::getScope)
                .filter(owner -> FIXED_FACTORY_OWNERS.contains(MethodCalls.receiverName(owner)))
                .isPresent();
    }

    // a && b: ключом может быть любая из частей; a || b по одному ключу уже не найти
    private Optional<KeyMatch> findKeyEquality(Expression condition, String element) {
        Expression value = Nodes.unwrap(condition);
        if (value.isBinaryExpr()) {
            BinaryExpr binary = value.asBinaryExpr();
            if (binary.getOperator() == BinaryExpr.Operator.AND) {
                return findKeyEquality(binary.getLeft(), element).or(() -> findKeyEquality(binary.getRight(), element));
            }
            return binary.getOperator() == BinaryExpr.Operator.EQUALS
                    ? match(binary.getLeft(), binary.getRight(), element)
                    : Optional.empty();
        }
        if (!value.isMethodCallExpr() || !EQUALS.equals(value.asMethodCallExpr().getNameAsString())) {
            return Optional.empty();
        }
        MethodCallExpr call = value.asMethodCallExpr();
        boolean helper = call.getArguments().size() == HELPER_ARGUMENTS
                && call.getScope().map(MethodCalls::receiverName).filter(EQUALS_HELPERS::contains).isPresent();
        if (helper) {
            return match(call.getArgument(0), call.getArgument(1), element);
        }
        return call.getArguments().size() == 1 && call.getScope().isPresent()
                ? match(call.getScope().get(), call.getArgument(0), element)
                : Optional.empty();
    }

    private Optional<KeyMatch> match(Expression left, Expression right, String element) {
        if (isProperty(left, element) && !mentions(right, element)) {
            return Optional.of(new KeyMatch(Nodes.unwrap(left), Nodes.unwrap(right)));
        }
        return isProperty(right, element) && !mentions(left, element)
                ? Optional.of(new KeyMatch(Nodes.unwrap(right), Nodes.unwrap(left)))
                : Optional.empty();
    }

    // item.getId(), item.getOwner().getId(), item.id: значение читается из самого элемента.
    // Сам элемент ключом не считается: поиск элемента целиком - это contains, для него нужен Set
    private boolean isProperty(Expression expression, String element) {
        Expression value = Nodes.unwrap(expression);
        if (value.isMethodCallExpr()) {
            MethodCallExpr call = value.asMethodCallExpr();
            return call.getArguments().isEmpty()
                    && call.getScope().filter(scope -> isElement(scope, element) || isProperty(scope, element)).isPresent();
        }
        if (value.isFieldAccessExpr()) {
            Expression scope = value.asFieldAccessExpr().getScope();
            return isElement(scope, element) || isProperty(scope, element);
        }
        return false;
    }

    private boolean isElement(Expression expression, String element) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr() && value.asNameExpr().getNameAsString().equals(element);
    }

    private boolean mentions(Expression expression, String element) {
        return expression.findAll(NameExpr.class).stream().anyMatch(name -> name.getNameAsString().equals(element));
    }

    // item -> условие либо item -> { return условие; }
    private Optional<Expression> conditionOf(LambdaExpr lambda) {
        if (lambda.getExpressionBody().isPresent()) {
            return lambda.getExpressionBody();
        }
        return Optional.of(lambda.getBody())
                .filter(Statement::isBlockStmt)
                .map(Statement::asBlockStmt)
                .filter(block -> block.getStatements().size() == 1 && block.getStatement(0).isReturnStmt())
                .flatMap(block -> block.getStatement(0).asReturnStmt().getExpression());
    }

    // stream().filter(...).findFirst(): следующая операция цепочки - та, для которой текущая служит объектом вызова
    private Optional<MethodCallExpr> nextInChain(MethodCallExpr call) {
        return call.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr)
                .map(parent -> (MethodCallExpr) parent)
                .filter(parent -> parent.getScope().filter(scope -> scope == call).isPresent());
    }

    private boolean stopsAtFirst(MethodCallExpr operation) {
        if (MATCHERS.contains(operation.getNameAsString())) {
            return true;
        }
        Optional<MethodCallExpr> next = nextInChain(operation);
        while (next.isPresent()) {
            if (FINDERS.contains(next.get().getNameAsString())) {
                return true;
            }
            next = nextInChain(next.get());
        }
        return false;
    }
}
