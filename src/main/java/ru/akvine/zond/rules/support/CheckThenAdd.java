package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.Optional;

/**
 * Проверка на повтор перед добавлением: {@code if (!items.contains(x)) items.add(x)}.
 * О такой паре сообщает одно правило (jr:410), поэтому правилу о contains в цикле нужно ее узнавать.
 */
@UtilityClass
public class CheckThenAdd {
    private final static String CONTAINS = "contains";
    private final static String ADD = "add";
    private final static String THIS_PREFIX = "this.";

    /**
     * @param contains вызов {@code коллекция.contains(элемент)}
     * @return добавление того же элемента в ту же коллекцию, которое стоит в этом же методе после проверки
     */
    public Optional<MethodCallExpr> addAfter(MethodCallExpr contains) {
        if (!isCall(contains, CONTAINS)) {
            return Optional.empty();
        }
        String receiver = receiver(contains);
        String element = contains.getArgument(0).toString();
        return Nodes.enclosingCallable(contains).flatMap(callable -> callable.findAll(MethodCallExpr.class).stream()
                .filter(call -> isCall(call, ADD))
                .filter(call -> receiver(call).equals(receiver) && call.getArgument(0).toString().equals(element))
                .filter(call -> isAfter(call, contains))
                .findFirst());
    }

    private boolean isCall(MethodCallExpr call, String name) {
        return name.equals(call.getNameAsString()) && call.getArguments().size() == 1 && call.getScope().isPresent();
    }

    // items и this.items - одна и та же коллекция
    private String receiver(MethodCallExpr call) {
        String scope = call.getScope().map(Expression::toString).orElse("");
        return scope.startsWith(THIS_PREFIX) ? scope.substring(THIS_PREFIX.length()) : scope;
    }

    private boolean isAfter(Node later, Node earlier) {
        return later.getBegin().isPresent() && earlier.getBegin().isPresent()
                && later.getBegin().get().isAfter(earlier.getBegin().get());
    }
}
