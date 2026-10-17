package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CollectionModificationInForEachRule extends AbstractRule {
    private static final String FOR_EACH = "forEach";
    private static final Set<String> MAP_VIEWS = Set.of("keySet", "values", "entrySet");
    private static final Set<String> MODIFYING_METHODS = Set.of(
            "add", "addAll", "addFirst", "addLast", "remove", "removeAll", "removeIf", "retainAll", "clear",
            "push", "pop", "poll");
    // Обход конкурентной коллекции не падает от ее изменения: итератор видит коллекцию на какой-то момент
    private static final Set<String> CONCURRENT_TYPES = Set.of(
            "ConcurrentMap", "ConcurrentHashMap", "ConcurrentNavigableMap", "ConcurrentSkipListMap",
            "ConcurrentSkipListSet", "CopyOnWriteArrayList", "CopyOnWriteArraySet", "ConcurrentLinkedQueue",
            "ConcurrentLinkedDeque", "BlockingQueue", "BlockingDeque", "LinkedBlockingQueue", "LinkedBlockingDeque",
            "ArrayBlockingQueue", "PriorityBlockingQueue", "DelayQueue", "LinkedTransferQueue");
    // Лямбда, которую вызовут потом, а не во время обхода: sendRequest(request, createCallback(() -> ...))
    private static final Pattern CALLBACK = Pattern.compile(".*([Cc]allback|[Ll]istener|Async).*|(then|when|on)[A-Z].*"
            + "|submit|execute|schedule|scheduleAtFixedRate|scheduleWithFixedDelay|subscribe");

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

    private void report(SourceFile sourceFile, Node body, Expression iterated, List<Violation> violations) {
        String collection = name(iterated).orElseThrow();
        if (isConcurrent(iterated)) {
            return;
        }
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            boolean modifies = MODIFYING_METHODS.contains(call.getNameAsString())
                    && call.getScope().filter(scope -> isSameCollection(scope, iterated)).isPresent();
            if (modifies && !isFollowedByExit(call) && !isInCallback(call, body)) {
                violations.add(violation(sourceFile, call,
                        "Коллекция '" + collection + "' изменяется методом " + call.getNameAsString()
                                + " внутри обхода по ней же: будет ConcurrentModificationException;"
                                + " используйте Iterator.remove(), removeIf() или копию коллекции"));
            }
        }
    }

    // Map<K, V> ipMap = new ConcurrentHashMap<>(): тип виден по объявлению либо по созданному объекту
    private boolean isConcurrent(Expression collection) {
        boolean declared = LocalTypes.typeOf(collection).filter(CONCURRENT_TYPES::contains).isPresent();
        return declared || LocalTypes.findInitializer(collection)
                .map(Nodes::unwrap)
                .filter(Expression::isObjectCreationExpr)
                .filter(creation -> CONCURRENT_TYPES.contains(creation.asObjectCreationExpr().getType().getNameAsString()))
                .isPresent();
    }

    // Изменение внутри лямбды, отданной обработчиком на потом: к моменту ее вызова обход уже закончен
    private boolean isInCallback(MethodCallExpr call, Node body) {
        Node current = call.getParentNode().orElse(null);
        while (current != null && current != body) {
            if (current instanceof LambdaExpr && current.getParentNode().orElse(null) instanceof MethodCallExpr owner
                    && CALLBACK.matcher(owner.getNameAsString()).matches()) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // items, this.items, map.keySet() / values() / entrySet()
    private Optional<Expression> iteratedCollection(Expression iterable) {
        Expression value = Nodes.unwrap(iterable);
        if (value.isMethodCallExpr() && MAP_VIEWS.contains(value.asMethodCallExpr().getNameAsString())) {
            value = value.asMethodCallExpr().getScope().map(Nodes::unwrap).orElse(value);
        }
        return name(value).isPresent() ? Optional.of(value) : Optional.empty();
    }

    /**
     * Та же коллекция, а не одноименная: в {@code for (String uuid : uuids) this.uuids.add(uuid);} обходят
     * параметр, а пополняют поле
     */
    private boolean isSameCollection(Expression modified, Expression iterated) {
        Optional<String> name = name(Nodes.unwrap(modified));
        if (name.isEmpty() || !name.equals(name(iterated))) {
            return false;
        }
        Optional<Node> modifiedDeclaration = LocalTypes.findDeclaration(modified);
        Optional<Node> iteratedDeclaration = LocalTypes.findDeclaration(iterated);
        return modifiedDeclaration.isEmpty() || iteratedDeclaration.isEmpty()
                || modifiedDeclaration.get() == iteratedDeclaration.get();
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
