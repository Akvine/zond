package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Объект, который собран в этом же методе, а не прочитан из базы: его сохранение - вставка новой строки,
 * а не обновление существующей
 */
@UtilityClass
public class NewObjects {
    // Order.builder()...build(), dataManager.create(Order.class), createNewEntity(dto), mapper.toEntity(dto)
    private static final Pattern CREATE_METHOD = Pattern.compile(
            "^(create|build|new|make|construct)([A-Z]\\w*)?$|^toEntity\\w*$");
    private static final Pattern CREATED_BY = Pattern.compile("(?i).*(mapper|converter|factory|builder).*");

    /**
     * @return true для new Order(...), цепочки от него (new Order().setTotal(...)), сборки билдером или
     * преобразователем и для переменной, которой такой объект присвоен
     */
    public boolean isNew(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (isCreation(value)) {
            return true;
        }
        if (!value.isNameExpr()) {
            return false;
        }
        if (LocalTypes.findInitializer(value).filter(NewObjects::isCreation).isPresent()) {
            return true;
        }
        // Order order; if (...) { order = new Order(); } else { order = found.get(); }
        String name = value.asNameExpr().getNameAsString();
        Optional<Node> callable = Nodes.enclosingCallable(value);
        return callable.isPresent() && callable.get().findAll(AssignExpr.class).stream()
                .filter(assignment -> assignment.getTarget().toString().equals(name))
                .anyMatch(assignment -> isCreation(assignment.getValue()));
    }

    private boolean isCreation(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isObjectCreationExpr()) {
            return true;
        }
        if (!value.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = value.asMethodCallExpr();
        if (CREATE_METHOD.matcher(call.getNameAsString()).matches()) {
            return true;
        }
        Optional<Expression> scope = call.getScope().map(Nodes::unwrap);
        if (scope.isEmpty()) {
            return false;
        }
        // new Order().setTotal(...).setCustomer(...): цепочка сеттеров возвращает тот же новый объект
        return scope.get().isObjectCreationExpr()
                || scope.get().isMethodCallExpr() && isCreation(scope.get())
                || CREATED_BY.matcher(MethodCalls.receiverName(scope.get())).matches();
    }
}
