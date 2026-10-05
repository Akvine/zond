package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CollectionModificationInForEachRule extends AbstractRule {
    private static final String FOR_EACH = "forEach";
    private static final Set<String> MAP_VIEWS = Set.of("keySet", "values", "entrySet");
    private static final Set<String> MODIFYING_METHODS = Set.of(
            "add", "addAll", "addFirst", "addLast", "remove", "removeAll", "removeIf", "retainAll", "clear",
            "push", "pop", "poll");

    @Override
    public String code() {
        return RuleCodes.COLLECTION_MODIFICATION_IN_FOR_EACH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет изменение коллекции внутри for-each по ней же";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (ForEachStmt loop : sourceFile.unit().findAll(ForEachStmt.class)) {
            iteratedCollection(loop.getIterable())
                    .ifPresent(collection -> report(sourceFile, loop.getBody(), collection, violations));
        }

        // list.forEach(item -> list.remove(item))
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (FOR_EACH.equals(call.getNameAsString()) && call.getArguments().size() == 1) {
                call.getScope()
                        .flatMap(this::iteratedCollection)
                        .ifPresent(collection -> report(sourceFile, call.getArgument(0), collection, violations));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private void report(SourceFile sourceFile, Node body, String collection, List<Violation> violations) {
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            boolean modifies = MODIFYING_METHODS.contains(call.getNameAsString())
                    && call.getScope().flatMap(this::name).filter(collection::equals).isPresent();
            if (modifies && !isFollowedByExit(call)) {
                violations.add(violation(sourceFile, call,
                        "Коллекция '" + collection + "' изменяется методом " + call.getNameAsString()
                                + " внутри обхода по ней же: будет ConcurrentModificationException;"
                                + " используйте Iterator.remove(), removeIf() или копию коллекции"));
            }
        }
    }

    // items, this.items, map.keySet() / values() / entrySet()
    private Optional<String> iteratedCollection(Expression iterable) {
        Expression value = Nodes.unwrap(iterable);
        if (value.isMethodCallExpr() && MAP_VIEWS.contains(value.asMethodCallExpr().getNameAsString())) {
            return value.asMethodCallExpr().getScope().flatMap(this::name);
        }
        return name(value);
    }

    private Optional<String> name(Expression expression) {
        if (expression.isNameExpr()) {
            return Optional.of(expression.asNameExpr().getNameAsString());
        }
        if (expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()) {
            return Optional.of(expression.asFieldAccessExpr().getNameAsString());
        }
        return Optional.empty();
    }

    // list.remove(item); break; - обход на этом заканчивается, исключения не будет
    private boolean isFollowedByExit(MethodCallExpr call) {
        Node statement = call;
        while (statement != null && !(statement instanceof Statement)) {
            statement = statement.getParentNode().orElse(null);
        }
        if (statement == null || !(statement.getParentNode().orElse(null) instanceof BlockStmt block)) {
            return false;
        }

        int next = block.getStatements().indexOf(statement) + 1;
        return next < block.getStatements().size()
                && (block.getStatement(next).isBreakStmt() || block.getStatement(next).isReturnStmt());
    }
}
