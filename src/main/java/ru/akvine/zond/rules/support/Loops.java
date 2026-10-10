package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Код, который выполняется многократно: тело цикла либо лямбда поэлементной операции (forEach, map, filter)
 */
@UtilityClass
public class Loops {
    // Операции, которые выполняют лямбду для каждого элемента
    private final static Set<String> ITERATING_METHODS = Set.of(
            "forEach", "forEachOrdered", "map", "flatMap", "filter", "peek", "anyMatch", "allMatch", "noneMatch",
            "mapToInt", "mapToLong", "mapToDouble", "mapToObj", "removeIf", "replaceAll", "computeIfAbsent");

    // Методы, которые есть и у стрима, и у Optional
    private final static Set<String> OPTIONAL_METHODS = Set.of("map", "flatMap", "filter");
    private final static Set<String> OPTIONAL_SOURCES =
            Set.of("findById", "findFirst", "findAny", "findOne", "ofNullable", "max", "min");
    private final static String OPTIONAL = "Optional";
    private final static Set<String> OPTIONAL_TYPES = Set.of("Optional", "OptionalInt", "OptionalLong", "OptionalDouble");
    private final static Pattern OPTIONAL_NAME = Pattern.compile("(?i).*optional$|^(optional|maybe).*|.*Opt$");

    /**
     * @return цикл или лямбда, внутри которых узел выполняется на каждой итерации
     */
    public Optional<Node> enclosingIteration(Node node) {
        Node child = node;
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof BodyDeclaration<?>)) {
            // for (User user : repository.findAll()) - источник цикла вычисляется один раз
            if (current instanceof ForEachStmt loop && loop.getBody() == child) {
                return Optional.of(current);
            }
            // Инициализация for выполняется один раз, условие, шаг и тело - на каждой итерации
            Node part = child;
            if (current instanceof ForStmt loop && loop.getInitialization().stream().noneMatch(init -> init == part)) {
                return Optional.of(current);
            }
            if (current instanceof WhileStmt || current instanceof DoStmt) {
                return Optional.of(current);
            }
            if (current instanceof LambdaExpr lambda && isIteratingLambda(lambda)) {
                return Optional.of(current);
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    public boolean isRepeated(Node node) {
        return enclosingIteration(node).isPresent();
    }

    /**
     * @return true, если переменная объявлена до цикла, а не заново на каждой итерации
     */
    public boolean isDeclaredOutside(Node iteration, Node declaration) {
        return !iteration.isAncestorOf(declaration);
    }

    // optional.map(value -> ...) выполняет лямбду не больше одного раза - это не перебор
    private boolean isOnOptional(MethodCallExpr call) {
        return OPTIONAL_METHODS.contains(call.getNameAsString()) && call.getScope().filter(Loops::isOptional).isPresent();
    }

    private boolean isOptional(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isNameExpr() && OPTIONAL_NAME.matcher(value.asNameExpr().getNameAsString()).matches()) {
            return true;
        }
        if (value.isMethodCallExpr()) {
            MethodCallExpr source = value.asMethodCallExpr();
            // repository.findById(id), stream.findFirst(), Optional.ofNullable(x)
            boolean returnsOptional = OPTIONAL_SOURCES.contains(source.getNameAsString())
                    || source.getScope().map(MethodCalls::receiverName).filter(OPTIONAL::equals).isPresent();
            // optional.filter(...).map(...): цепочка остается Optional
            boolean chained = OPTIONAL_METHODS.contains(source.getNameAsString())
                    && source.getScope().filter(Loops::isOptional).isPresent();
            if (returnsOptional || chained) {
                return true;
            }
        }
        return LocalTypes.typeOf(value).filter(OPTIONAL_TYPES::contains).isPresent();
    }

    // items.forEach(item -> ...), stream.map(item -> ...)
    private boolean isIteratingLambda(LambdaExpr lambda) {
        return lambda.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr)
                .map(parent -> (MethodCallExpr) parent)
                .filter(call -> ITERATING_METHODS.contains(call.getNameAsString()) && !isOnOptional(call))
                .isPresent();
    }
}
