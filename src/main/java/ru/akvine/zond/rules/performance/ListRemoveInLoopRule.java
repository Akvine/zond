package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Удаление из списка по одному элементу в цикле. ArrayList при каждом удалении сдвигает хвост, а remove(Object)
 * еще и ищет элемент перебором: одно удаление - O(n), весь цикл - O(n²). removeIf проходит список один раз
 * и сдвигает оставшиеся элементы за тот же проход.
 */
@Component
public class ListRemoveInLoopRule extends AbstractRule {
    private static final String REMOVE = "remove";
    private static final Set<String> ITERATOR_SOURCES = Set.of("iterator", "listIterator");
    // У LinkedList удаление через итератор стоит O(1), у множеств и карт - тоже: речь только о списке на массиве
    private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "Vector", "CopyOnWriteArrayList");
    private static final Set<String> ITERATOR_TYPES = Set.of("Iterator", "ListIterator");
    // list.remove(list.size() - 1): удаление с конца ничего не сдвигает
    private static final String LAST_INDEX = "size() - 1";

    @Override
    public String code() {
        return RuleCodes.LIST_REMOVE_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет удаление из списка по одному элементу в цикле вместо removeIf";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!REMOVE.equals(call.getNameAsString()) || call.getScope().isEmpty()) {
                continue;
            }
            Optional<Node> iteration = Loops.enclosingIteration(call);
            // Удалили и вышли из цикла: удаление одно
            if (iteration.isEmpty() || Loops.endsIteration(call)) {
                continue;
            }
            Expression scope = Nodes.unwrap(call.getScope().get());
            if (call.getArguments().isEmpty()) {
                iteratedList(scope).ifPresent(list -> violations.add(violation(sourceFile, call,
                        "'" + call + "' в цикле по списку '" + list + "': каждое удаление из ArrayList сдвигает"
                                + " хвост списка - O(n), а весь цикл - O(n²); " + list + ".removeIf(...) удаляет"
                                + " все подходящие элементы за один проход")));
            } else if (call.getArguments().size() == 1 && isList(scope) && !isCheap(call)
                    && !iteratesOver(iteration.get(), scope)) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' в цикле: каждое удаление из ArrayList ищет элемент перебором и сдвигает"
                                + " хвост списка - O(n), а весь цикл - O(n²); удалите все за один проход: "
                                + MethodCalls.receiverName(scope) + ".removeIf(...) либо removeAll(...) с множеством"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private boolean isList(Expression scope) {
        return LocalTypes.typeOf(scope).filter(LIST_TYPES::contains).isPresent();
    }

    /**
     * @return список, по которому идет итератор: {@code Iterator<Order> it = orders.iterator(); ... it.remove()}
     */
    private Optional<String> iteratedList(Expression iterator) {
        if (LocalTypes.typeOf(iterator).filter(ITERATOR_TYPES::contains).isEmpty()) {
            return Optional.empty();
        }
        return LocalTypes.findInitializer(iterator)
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(source -> ITERATOR_SOURCES.contains(source.getNameAsString()))
                .flatMap(MethodCallExpr::getScope)
                .filter(this::isList)
                .map(list -> MethodCalls.receiverName(list));
    }

    // remove(0) и remove(size() - 1): о первом сообщает правило о сдвиге списка, второе ничего не сдвигает
    private boolean isCheap(MethodCallExpr call) {
        Expression argument = Nodes.unwrap(call.getArgument(0));
        return argument.isIntegerLiteralExpr() || argument.toString().endsWith(LAST_INDEX);
    }

    // for (Order order : orders) orders.remove(order): это ConcurrentModificationException, о нем - отдельное правило
    private boolean iteratesOver(Node iteration, Expression list) {
        return iteration instanceof ForEachStmt loop
                && Nodes.unwrap(loop.getIterable()).toString().equals(list.toString());
    }
}
