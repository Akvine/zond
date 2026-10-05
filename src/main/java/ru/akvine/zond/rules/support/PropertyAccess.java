package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.Optional;

/**
 * Запись и чтение свойств объекта: сеттеры, геттеры и методы построителя
 */
@UtilityClass
public class PropertyAccess {
    private static final String SETTER_PREFIX = "set";
    private static final String GETTER_PREFIX = "get";
    private static final String BUILDER = "builder";

    /**
     * @return свойство, которое задает метод: setPassword -> password; у построителя имя метода и есть свойство
     */
    public String writtenProperty(String method) {
        boolean isSetter = method.startsWith(SETTER_PREFIX) && method.length() > SETTER_PREFIX.length()
                && Character.isUpperCase(method.charAt(SETTER_PREFIX.length()));
        return isSetter ? decapitalize(method.substring(SETTER_PREFIX.length())) : method;
    }

    /**
     * @return свойство, которое читает геттер: getPassword -> password; пусто, если метод - не геттер
     */
    public Optional<String> readProperty(MethodCallExpr call) {
        String method = call.getNameAsString();
        boolean isGetter = call.getArguments().isEmpty() && method.startsWith(GETTER_PREFIX)
                && method.length() > GETTER_PREFIX.length() && Character.isUpperCase(method.charAt(GETTER_PREFIX.length()));
        return isGetter ? Optional.of(decapitalize(method.substring(GETTER_PREFIX.length()))) : Optional.empty();
    }

    /**
     * @return тип объекта, у которого вызывают метод: для user.setName(...) - тип переменной user,
     * для new User().setName(...) и User.builder().name(...) - User; пусто, если тип по коду не виден
     */
    public Optional<String> ownerType(Expression scope) {
        Expression root = Nodes.unwrap(scope);
        while (root.isMethodCallExpr() && root.asMethodCallExpr().getScope().isPresent()) {
            MethodCallExpr call = root.asMethodCallExpr();
            Expression owner = Nodes.unwrap(call.getScope().get());
            if (call.getNameAsString().equals(BUILDER) && owner.isNameExpr()) {
                return Optional.of(owner.asNameExpr().getNameAsString());
            }
            root = owner;
        }
        if (root.isObjectCreationExpr()) {
            return Optional.of(root.asObjectCreationExpr().getType().getNameAsString());
        }
        return root == Nodes.unwrap(scope) ? LocalTypes.typeOf(root) : Optional.empty();
    }

    public String decapitalize(String name) {
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
