package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;

@UtilityClass
class MethodCalls {

    /**
     * @return true для вызова вида Type.method(...), в том числе с полным именем типа: java.time.Type.method(...)
     */
    boolean isCallOn(MethodCallExpr call, String typeName, String methodName) {
        return call.getNameAsString().equals(methodName)
                && call.getScope().filter(scope -> isType(scope, typeName)).isPresent()
                // Собственный класс проекта с тем же именем (свой Files, свой Objects) - это не библиотечный тип
                && !Types.isDeclaredInProject(call);
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

    /**
     * @param candidates методы класса, среди которых ищется вызванный
     * @return метод, на который приходится вызов. Перегрузки различаются по разрешенному вызову,
     * а если разрешить его не удалось - по числу аргументов
     */
    Optional<MethodDeclaration> findCallee(MethodCallExpr call, List<MethodDeclaration> candidates) {
        List<MethodDeclaration> sameShape = candidates.stream()
                .filter(method -> method.getNameAsString().equals(call.getNameAsString()))
                .filter(method -> method.getParameters().size() == call.getArguments().size())
                .toList();
        if (sameShape.isEmpty()) {
            return Optional.empty();
        }

        // save(Order) и save(Long): решатель знает, какая из перегрузок вызвана, - находим ее по строке объявления.
        // Среди кандидатов нужной может и не быть: тогда вызов к ним не относится
        Optional<Integer> line = Types.declarationLine(call);
        if (line.isEmpty()) {
            return sameShape.stream().findFirst();
        }
        return sameShape.stream()
                .filter(method -> method.getBegin().filter(position -> position.line == line.get()).isPresent())
                .findFirst();
    }

    // Имя типа в коде сравниваем по простому имени: так совпадают и короткая, и полная запись
    boolean isType(Expression expression, String typeName) {
        if (expression.isNameExpr()) {
            return expression.asNameExpr().getNameAsString().equals(typeName);
        }
        return expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getNameAsString().equals(typeName);
    }
}
