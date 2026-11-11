package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Откуда взят поток ввода-вывода: файловый поток без буфера каждое чтение и каждую запись отдает операционной
 * системе отдельным вызовом
 */
@UtilityClass
public class StreamSources {
    private static final Set<String> FILE_STREAMS = Set.of("FileInputStream", "FileOutputStream");
    private static final Set<String> FILES_FACTORIES = Set.of("newInputStream", "newOutputStream");
    private static final String FILES = "Files";

    /**
     * @return все, что кладут в локальную переменную в ее методе: инициализатор и присваивания, кроме null.
     * Для выражения, которое само не переменная, - оно же. Для параметра и поля список пуст: что в них лежит,
     * по одному методу не видно
     */
    public List<Expression> valuesOf(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isNameExpr()) {
            return List.of(value);
        }
        String name = value.asNameExpr().getNameAsString();
        Optional<CallableDeclaration> callable = value.findAncestor(CallableDeclaration.class);
        if (callable.isEmpty()) {
            return List.of();
        }
        List<Expression> values = new ArrayList<>();
        for (VariableDeclarator variable : callable.get().findAll(VariableDeclarator.class)) {
            if (variable.getNameAsString().equals(name)) {
                variable.getInitializer().ifPresent(values::add);
            }
        }
        for (AssignExpr assignment : callable.get().findAll(AssignExpr.class)) {
            if (assignment.getTarget().isNameExpr()
                    && assignment.getTarget().asNameExpr().getNameAsString().equals(name)) {
                values.add(assignment.getValue());
            }
        }
        return values.stream().map(Nodes::unwrap).filter(assigned -> !assigned.isNullLiteralExpr()).toList();
    }

    /**
     * @return true для new FileInputStream(file), new FileOutputStream(file), Files.newInputStream(path)
     * и Files.newOutputStream(path)
     */
    public boolean isRawFileStream(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isObjectCreationExpr()) {
            return FILE_STREAMS.contains(value.asObjectCreationExpr().getType().getNameAsString());
        }
        return value.isMethodCallExpr()
                && FILES_FACTORIES.contains(value.asMethodCallExpr().getNameAsString())
                && MethodCalls.isCallOn(value.asMethodCallExpr(), FILES, value.asMethodCallExpr().getNameAsString());
    }

    /**
     * @return создание файлового потока без буфера, если выражение - оно само либо переменная, в которую
     * ничего другого не кладут
     */
    public Optional<Expression> rawFileStream(Expression expression) {
        List<Expression> values = valuesOf(expression);
        return !values.isEmpty() && values.stream().allMatch(StreamSources::isRawFileStream)
                ? Optional.of(values.get(0))
                : Optional.empty();
    }
}
