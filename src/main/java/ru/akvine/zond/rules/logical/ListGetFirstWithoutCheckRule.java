package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.ElementTypes;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ListGetFirstWithoutCheckRule extends AbstractRule {
    private static final String GET = "get";
    private static final String FIRST_INDEX = "0";
    private static final Set<String> CAUGHT = Set.of(
            "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException", "RuntimeException", "Exception",
            "Throwable");
    private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList");

    // Методы, которые заведомо возвращают список: stream.toList().get(0), repository.findAll().get(0)
    private static final Set<String> LIST_SOURCES = Set.of("toList", "findAll");

    // list.isEmpty(), list.size(), CollectionUtils.isEmpty(list)
    private static final Set<String> SIZE_CHECKS = Set.of("isEmpty", "size", "isNotEmpty");
    private static final String THIS_PREFIX = "this.";
    // Фабрики, которые возвращают список ровно из переданных элементов
    private static final Set<String> FILLED_FACTORIES = Set.of("of", "asList", "listOf");
    private static final Set<String> GROUPING_METHODS = Set.of("groupingBy", "groupingByConcurrent");
    // addAll(other) сюда не входит: другой список может оказаться пустым
    private static final Set<String> ADDING_METHODS = Set.of("add", "addFirst", "addLast", "push");
    private static final Set<String> REMOVING_METHODS = Set.of("clear", "remove", "removeAll", "removeIf", "retainAll",
            "removeFirst", "removeLast", "poll", "pop", "subList");

    @Override
    public String code() {
        return RuleCodes.LIST_GET_FIRST_WITHOUT_CHECK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет list.get(0) без проверки списка на пустоту";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.findAll(MethodCallExpr.class).stream()
                .filter(call -> GET.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getArgument(0).isIntegerLiteralExpr()
                        && FIRST_INDEX.equals(call.getArgument(0).asIntegerLiteralExpr().getValue()))
                .filter(call -> call.getScope().filter(scope -> isUncheckedList(scope, call)).isPresent())
                // В тестах размер списка проверяют утверждением (hasSize, assertEquals), а не условием
                .filter(call -> !TestClasses.isInside(call))
                // try { value = list.get(0); } catch (IndexOutOfBoundsException e) { ... }: пустой список уже учтен
                .filter(call -> !CodeContexts.isCaught(call, CAUGHT))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' без проверки на пустоту: на пустом списке будет IndexOutOfBoundsException;"
                                + " проверьте isEmpty() либо используйте stream().findFirst()"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isUncheckedList(Expression scope, MethodCallExpr call) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return LIST_SOURCES.contains(value.asMethodCallExpr().getNameAsString());
        }
        return LocalTypes.typeOf(value).filter(LIST_TYPES::contains).isPresent() && !isSizeChecked(value, call)
                && !isFilledConstant(value) && !isFilledBefore(value, call) && !isGroup(value);
    }

    /**
     * {@code byId.values().forEach(group -> group.get(0))}, {@code for (List<Order> group : byId.values())}, где
     * {@code byId} собран через Collectors.groupingBy: группа появляется вместе с первым элементом, пустой не бывает
     */
    private boolean isGroup(Expression list) {
        Optional<Expression> collection = LocalTypes.findDeclaration(list).flatMap(ElementTypes::collectionOf);
        return collection.filter(this::isGrouping).isPresent()
                || collection.flatMap(LocalTypes::findInitializer).filter(this::isGrouping).isPresent();
    }

    private boolean isGrouping(Expression collection) {
        return collection.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> GROUPING_METHODS.contains(call.getNameAsString()));
    }

    /**
     * {@code names.add(name); return names.get(0);} и {@code List<String> names = List.of(first, second);}:
     * список пополнен в этом же блоке, выше по коду и без условий - пустым он быть не может
     */
    private boolean isFilledBefore(Expression list, MethodCallExpr call) {
        String name = list.toString();
        Node child = call;
        Node current = call.getParentNode().orElse(null);
        while (current != null && !(current instanceof CallableDeclaration<?>)) {
            if (current instanceof BlockStmt block && isFilledIn(block, child, name)) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // Операторы блока до того, в котором стоит обращение: последнее, что сделали со списком, - добавили элемент
    private boolean isFilledIn(BlockStmt block, Node until, String list) {
        boolean filled = false;
        for (Statement statement : block.getStatements()) {
            if (statement == until) {
                break;
            }
            if (fills(statement, list)) {
                filled = true;
            } else if (mayEmpty(statement, list)) {
                filled = false;
            }
        }
        return filled;
    }

    private boolean fills(Statement statement, String list) {
        if (!statement.isExpressionStmt()) {
            return false;
        }
        Expression expression = Nodes.unwrap(statement.asExpressionStmt().getExpression());
        if (expression.isMethodCallExpr()) {
            MethodCallExpr call = expression.asMethodCallExpr();
            return ADDING_METHODS.contains(call.getNameAsString())
                    && call.getScope().filter(scope -> scope.toString().equals(list)).isPresent();
        }
        // List<String> names = List.of(first, second)
        return expression.isVariableDeclarationExpr() && expression.asVariableDeclarationExpr().getVariables().stream()
                .filter(variable -> variable.getNameAsString().equals(list))
                .flatMap(variable -> variable.getInitializer().stream())
                .map(Nodes::unwrap)
                .anyMatch(initializer -> initializer.isMethodCallExpr()
                        && FILLED_FACTORIES.contains(initializer.asMethodCallExpr().getNameAsString())
                        && !initializer.asMethodCallExpr().getArguments().isEmpty());
    }

    // clear(), remove(...), новое присваивание, передача в другой метод: что после этого в списке, неизвестно.
    // Чтение - get, size, stream - список не опустошает
    private boolean mayEmpty(Statement statement, String list) {
        boolean reassigned = statement.findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().toString().equals(list));
        return reassigned || statement.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getArguments().stream().anyMatch(argument -> argument.toString().equals(list))
                        || REMOVING_METHODS.contains(call.getNameAsString())
                        && call.getScope().filter(scope -> scope.toString().equals(list)).isPresent());
    }

    // static final List<String> SCOPES = List.of("singleton", "application"): постоянный список с элементами
    private boolean isFilledConstant(Expression list) {
        return LocalTypes.findDeclaration(list)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .map(declaration -> (VariableDeclarator) declaration)
                .filter(variable -> variable.getParentNode()
                        .filter(parent -> parent instanceof FieldDeclaration field && field.isStatic() && field.isFinal())
                        .isPresent())
                .flatMap(VariableDeclarator::getInitializer)
                .map(Nodes::unwrap)
                .filter(initializer -> initializer.isMethodCallExpr()
                        && FILLED_FACTORIES.contains(initializer.asMethodCallExpr().getNameAsString())
                        && !initializer.asMethodCallExpr().getArguments().isEmpty())
                .isPresent();
    }

    // Проверка размера должна стоять на пути к обращению: охватывать его либо обрывать выполнение выше
    private boolean isSizeChecked(Expression list, MethodCallExpr call) {
        String name = list.toString();
        return Guards.isGuarded(call, check -> isSizeCheck(check, name));
    }

    // list.isEmpty(), list.size(), CollectionUtils.isEmpty(list)
    private boolean isSizeCheck(Expression expression, String list) {
        if (!expression.isMethodCallExpr() || !SIZE_CHECKS.contains(expression.asMethodCallExpr().getNameAsString())) {
            return false;
        }
        MethodCallExpr check = expression.asMethodCallExpr();
        return check.getScope().filter(scope -> scope.toString().equals(list)).isPresent()
                || check.getArguments().stream().anyMatch(argument -> argument.toString().equals(list))
                || isOwnCheckOf(check, list);
    }

    // if (!isEmpty()) { return references.get(0); } - свой isEmpty() без объекта, который смотрит в тот же список
    private boolean isOwnCheckOf(MethodCallExpr check, String list) {
        if (!check.getArguments().isEmpty() || check.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
            return false;
        }
        String field = list.startsWith(THIS_PREFIX) ? list.substring(THIS_PREFIX.length()) : list;
        return check.findAncestor(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(type -> type.getMethodsByName(check.getNameAsString()).stream())
                .filter(method -> method.getParameters().isEmpty())
                .anyMatch(method -> method.findAll(NameExpr.class).stream()
                        .anyMatch(name -> name.getNameAsString().equals(field))
                        || method.findAll(FieldAccessExpr.class).stream()
                        .anyMatch(access -> access.getNameAsString().equals(field)));
    }
}
