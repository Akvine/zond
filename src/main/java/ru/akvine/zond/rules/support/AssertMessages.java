package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * Сообщение проверки в тестах: в JUnit 4 оно идет первым аргументом ({@code assertTrue("not sent", count >= 1)}),
 * в JUnit 5 - последним
 */
@UtilityClass
public class AssertMessages {
    private final static Set<String> FORMAT_METHODS = Set.of("format", "formatted");

    /**
     * @return true для текста: строки, сцепления со строкой ({@code "wrong size on " + i}), String.format(...)
     */
    public boolean isText(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isStringLiteralExpr() || value.isTextBlockLiteralExpr()) {
            return true;
        }
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            return isText(value.asBinaryExpr().getLeft()) || isText(value.asBinaryExpr().getRight());
        }
        return value.isMethodCallExpr() && FORMAT_METHODS.contains(value.asMethodCallExpr().getNameAsString());
    }
}
