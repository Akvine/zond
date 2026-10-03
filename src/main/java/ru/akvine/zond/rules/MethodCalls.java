package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

@UtilityClass
class MethodCalls {

    /**
     * @return true для вызова вида Type.method(...), в том числе с полным именем типа: java.time.Type.method(...)
     */
    boolean isCallOn(MethodCallExpr call, String typeName, String methodName) {
        return call.getNameAsString().equals(methodName)
                && call.getScope().filter(scope -> isType(scope, typeName)).isPresent();
    }

    /**
     * @return последнее имя в выражении, на котором вызван метод:
     * this.userRepository -> userRepository, getClient() -> getClient, Files -> Files
     */
    String receiverName(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isFieldAccessExpr()) {
            return value.asFieldAccessExpr().getNameAsString();
        }
        if (value.isMethodCallExpr()) {
            return value.asMethodCallExpr().getNameAsString();
        }
        if (value.isTypeExpr()) {
            return LocalTypes.typeName(value.asTypeExpr().getType());
        }
        return value.toString();
    }

    // Типы не разрешаем, поэтому сравниваем по простому имени
    boolean isType(Expression expression, String typeName) {
        if (expression.isNameExpr()) {
            return expression.asNameExpr().getNameAsString().equals(typeName);
        }
        return expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getNameAsString().equals(typeName);
    }
}
