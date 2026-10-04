package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckSynchronizedCollectionIterationRule extends AbstractRule {
    private static final String COLLECTIONS = "Collections";
    private static final String SYNCHRONIZED_PREFIX = "synchronized";

    @Override
    public String code() {
        return RuleCodes.CHECK_SYNCHRONIZED_COLLECTION_ITERATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обход Collections.synchronized... без блока synchronized";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(ForEachStmt.class).stream()
                .filter(loop -> isSynchronizedWrapper(loop.getIterable()) && !isInsideSynchronized(loop))
                .map(loop -> violation(sourceFile, loop,
                        "Обход '" + loop.getIterable() + "' без synchronized: обертка Collections.synchronized..."
                                + " защищает отдельные вызовы, но не обход - изменение из другого потока даст"
                                + " ConcurrentModificationException; оберните цикл в synchronized ("
                                + loop.getIterable() + ")"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private boolean isSynchronizedWrapper(Expression iterable) {
        return LocalTypes.findInitializer(iterable)
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> call.getNameAsString().startsWith(SYNCHRONIZED_PREFIX)
                        && call.getScope().filter(scope -> MethodCalls.isType(scope, COLLECTIONS)).isPresent())
                .isPresent();
    }

    private boolean isInsideSynchronized(ForEachStmt loop) {
        String collection = loop.getIterable().toString();
        Node current = loop.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof SynchronizedStmt block && block.getExpression().toString().equals(collection)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
