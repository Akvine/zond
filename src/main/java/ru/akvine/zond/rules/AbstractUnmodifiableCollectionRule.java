package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Изменение коллекции, которая этого не допускает: вызов закончится UnsupportedOperationException
 */
public abstract class AbstractUnmodifiableCollectionRule extends AbstractRule {

    /**
     * @return true, если выражение создает коллекцию, которую нельзя менять
     */
    protected abstract boolean isUnmodifiableSource(MethodCallExpr call);

    /**
     * @return методы, которые на такой коллекции бросают исключение
     */
    protected abstract Set<String> modifyingMethods();

    /**
     * @return текст нарушения
     */
    protected abstract String message(MethodCallExpr call, String source);

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> modifyingMethods().contains(call.getNameAsString()))
                .flatMap(call -> call.getScope()
                        .flatMap(this::findSource)
                        .map(source -> violation(sourceFile, call, message(call, source)))
                        .stream())
                .toList();
    }

    /**
     * @return выражение, создавшее коллекцию: либо она создана прямо в цепочке вызова,
     * либо это переменная, которой такая коллекция присвоена при объявлении и больше не менялась
     */
    private Optional<String> findSource(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return Optional.of(value.asMethodCallExpr())
                    .filter(this::isUnmodifiableSource)
                    .map(this::describe);
        }

        return LocalTypes.findInitializer(value)
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(this::isUnmodifiableSource)
                .filter(source -> !isReassigned(value))
                .map(this::describe);
    }

    // Arrays.asList(...), Map.of(...)
    private String describe(MethodCallExpr source) {
        return source.getScope().map(scope -> scope + ".").orElse("") + source.getNameAsString() + "(...)";
    }

    // Переменной позже присвоили что-то другое - что в ней лежит к моменту вызова, неизвестно
    private boolean isReassigned(Expression variable) {
        String name = variable.isFieldAccessExpr()
                ? variable.asFieldAccessExpr().getNameAsString()
                : variable.toString();

        Node type = variable.getParentNode().orElse(null);
        while (type != null && !(type instanceof TypeDeclaration<?>)) {
            type = type.getParentNode().orElse(null);
        }
        return type != null && type.findAll(AssignExpr.class).stream()
                .map(AssignExpr::getTarget)
                .anyMatch(target -> target.toString().equals(name) || target.toString().equals("this." + name));
    }
}
