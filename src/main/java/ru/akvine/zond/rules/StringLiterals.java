package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@UtilityClass
class StringLiterals {

    /**
     * Строка, текст которой известен целиком: литерал либо конкатенация одних литералов
     */
    record LiteralText(Node node, String text) {
    }

    /**
     * @return все строки файла, собранные только из литералов. Строки, к которым приклеиваются переменные,
     * сюда не попадают: их полный текст по коду не узнать
     */
    List<LiteralText> findComplete(Node root) {
        List<LiteralText> literals = new ArrayList<>();
        for (Expression expression : root.findAll(Expression.class)) {
            boolean isString = expression.isStringLiteralExpr() || expression.isTextBlockLiteralExpr();
            boolean isConcatenation = expression.isBinaryExpr()
                    && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS;
            if ((isString || isConcatenation) && !isPartOfConcatenation(expression)) {
                textOf(expression).ifPresent(text -> literals.add(new LiteralText(expression, text)));
            }
        }
        return literals;
    }

    /**
     * @return текст выражения, если оно состоит только из строковых литералов
     */
    Optional<String> textOf(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isStringLiteralExpr()) {
            return Optional.of(value.asStringLiteralExpr().asString());
        }
        if (value.isTextBlockLiteralExpr()) {
            return Optional.of(value.asTextBlockLiteralExpr().asString());
        }
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = textOf(value.asBinaryExpr().getLeft());
            Optional<String> right = textOf(value.asBinaryExpr().getRight());
            return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
        }
        return Optional.empty();
    }

    // "a" + b + "c" разбирается как ("a" + b) + "c": берем только самое внешнее выражение
    private boolean isPartOfConcatenation(Expression expression) {
        Node parent = expression.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS;
    }
}
