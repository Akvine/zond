package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Обращение к коллекции-связи элемента цикла: for (Order order : orders) order.getItems().size().
 * Для LAZY-связи это отдельный запрос к БД на каждой итерации.
 */
public abstract class AbstractRelationAccessInLoopRule extends AbstractRule {
    // getItems(), getOrders()
    private static final Pattern GETTER = Pattern.compile("^get[A-Z].*");

    /**
     * @return метод коллекции, вызов которого проверяется: size либо contains
     */
    protected abstract String collectionMethod();

    /**
     * @return сколько аргументов у этого метода
     */
    protected abstract int argumentsCount();

    /**
     * @return текст нарушения
     */
    protected abstract String message(String access);

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Вне кода, работающего с БД, getItems().size() - обычный вызов, а не обращение к связи
        if (!JpaEntities.isPersistenceCode(sourceFile.unit())) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!collectionMethod().equals(call.getNameAsString())
                    || call.getArguments().size() != argumentsCount()
                    || call.getScope().isEmpty()) {
                continue;
            }

            Optional<Node> iteration = Loops.enclosingIteration(call);
            if (iteration.isPresent() && isGetterOnLoopElement(call.getScope().get(), iteration.get())) {
                violations.add(violation(sourceFile, call, message(call.getScope().get() + "." + collectionMethod())));
            }
        }
        return violations;
    }

    // Объект, у которого берут связь, должен меняться от итерации к итерации: это переменная цикла
    // или параметр лямбды. Связь объекта, объявленного до цикла, загружается один раз
    private boolean isGetterOnLoopElement(Expression scope, Node iteration) {
        Expression value = Nodes.unwrap(scope);
        if (!value.isMethodCallExpr()
                || !GETTER.matcher(value.asMethodCallExpr().getNameAsString()).matches()
                || !value.asMethodCallExpr().getArguments().isEmpty()) {
            return false;
        }

        return value.asMethodCallExpr().getScope()
                .filter(Expression::isNameExpr)
                .flatMap(LocalTypes::findDeclaration)
                .filter(declaration -> !Loops.isDeclaredOutside(iteration, declaration))
                .isPresent();
    }
}
