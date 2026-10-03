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

    // Типы не разрешаем, поэтому сравниваем по простому имени
    boolean isType(Expression expression, String typeName) {
        if (expression.isNameExpr()) {
            return expression.asNameExpr().getNameAsString().equals(typeName);
        }
        return expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getNameAsString().equals(typeName);
    }
}
