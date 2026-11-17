package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Тип элемента по тому, как объявлена коллекция, которую перебирают: у параметра лямбды и у переменной var цикла
 * for-each своего типа в коде нет.
 * <pre>
 *   orders.stream().filter(order -> order.isPaid())          // List&lt;Order&gt; orders          -> Order
 *   futures.forEach((index, future) -> future.join())        // Map&lt;Integer, Future&gt; futures -> Future
 *   for (var item : request.getItems()) { ... }              // List&lt;Item&gt; items у запроса  -> Item
 * </pre>
 * Решатель типов выводит такой тип сам, но только если знает все типы цепочки. Геттеров Lombok в исходниках нет,
 * классов библиотек без classpath тоже, и на цепочке, которая начинается с request.getItems(), он сдается. Запись
 * же {@code List<Item>} в объявлении поля видна всегда.
 */
@UtilityClass
public class ElementTypes {
    // Операции, лямбда которых получает элемент коллекции, стрима или значение Optional
    private static final Set<String> ELEMENT_METHODS = Set.of("forEach", "forEachOrdered", "filter", "map", "flatMap",
            "mapToInt", "mapToLong", "mapToDouble", "mapToObj", "peek", "anyMatch", "allMatch", "noneMatch",
            "takeWhile", "dropWhile", "removeIf", "ifPresent", "ifPresentOrElse", "thenApply", "thenAccept",
            "thenCompose", "doOnNext");
    // Сравнение двух элементов: sorted((left, right) -> ...)
    private static final Set<String> COMPARING_METHODS = Set.of("sorted", "min", "max", "sort");
    // orders.stream().filter(...).sorted().limit(10): элемент остается тем же
    private static final Set<String> SAME_ELEMENT_METHODS = Set.of("stream", "parallelStream", "filter", "peek",
            "sorted", "distinct", "limit", "skip", "takeWhile", "dropWhile", "parallel", "sequential", "unordered");
    // Collectors.toMap(order -> ...), Comparator.comparing(order -> ...) внутри collect(...) и sorted(...):
    // лямбда получает элемент того стрима, на котором вызван collect
    private static final Set<String> ELEMENT_FACTORIES = Set.of("Collectors", "Comparator");
    private static final Set<String> FACTORY_CONSUMERS = Set.of("collect", "sorted", "min", "max", "sort");
    private static final String FOR_EACH = "forEach";
    private static final String VALUES = "values";
    private static final String KEY_SET = "keySet";
    private static final String MAP_SUFFIX = "Map";
    private static final int KEY = 0;
    private static final int VALUE = 1;
    private static final int PAIR = 2;

    // Что именно перебирают: саму коллекцию, значения карты или ее ключи
    private enum View {
        ITSELF, VALUES, KEYS
    }

    private record Source(Expression expression, View view) {
    }

    /**
     * @return тип элемента, который получает параметр лямбды без типа; пусто, если по объявлению коллекции его
     * не определить
     */
    public Optional<Type> of(Parameter parameter) {
        if (!parameter.getType().isUnknownType()
                || !(parameter.getParentNode().orElse(null) instanceof LambdaExpr lambda)
                || !(lambda.getParentNode().orElse(null) instanceof MethodCallExpr call)) {
            return Optional.empty();
        }
        int position = positionOf(lambda.getParameters(), parameter);
        int parameters = lambda.getParameters().size();
        Optional<Expression> iterated = iteratedBy(call, parameters);
        if (iterated.isEmpty()) {
            return Optional.empty();
        }
        Source source = sourceOf(iterated.get());
        boolean pairOfMap = FOR_EACH.equals(call.getNameAsString()) && parameters == PAIR;
        return writtenType(source.expression())
                .flatMap(type -> elementOf(type, source.view(), pairOfMap ? position : -1));
    }

    /**
     * @return тип элемента для переменной цикла {@code for (var item : items)}
     */
    public Optional<Type> of(VariableDeclarator variable) {
        Optional<ForEachStmt> loop = variable.getParentNode()
                .flatMap(Node::getParentNode)
                .filter(ForEachStmt.class::isInstance)
                .map(ForEachStmt.class::cast);
        if (loop.isEmpty()) {
            return Optional.empty();
        }
        Source source = sourceOf(loop.get().getIterable());
        return writtenType(source.expression()).flatMap(type -> elementOf(type, source.view(), -1));
    }

    /**
     * @return выражение, элементы которого получает параметр лямбды или переменная цикла for-each: для
     * {@code orders.stream().filter(...).map(order -> ...)} это {@code orders.stream().filter(...)}
     */
    public Optional<Expression> iteratedBy(Node declaration) {
        if (declaration instanceof Parameter parameter
                && parameter.getParentNode().orElse(null) instanceof LambdaExpr lambda
                && lambda.getParentNode().orElse(null) instanceof MethodCallExpr call) {
            return iteratedBy(call, lambda.getParameters().size());
        }
        if (declaration instanceof VariableDeclarator variable) {
            return variable.getParentNode()
                    .flatMap(Node::getParentNode)
                    .filter(ForEachStmt.class::isInstance)
                    .map(loop -> ((ForEachStmt) loop).getIterable());
        }
        return Optional.empty();
    }

    /**
     * @return операции, через которые элемент дошел до лямбды или цикла, оставшись тем же, от ближней к дальней: для
     * {@code orders.stream().filter(paid).sorted().map(order -> ...)} это sorted(), filter(paid), stream()
     */
    public List<MethodCallExpr> passedBy(Node declaration) {
        List<MethodCallExpr> passed = new ArrayList<>();
        Expression current = iteratedBy(declaration).map(Nodes::unwrap).orElse(null);
        while (current != null && current.isMethodCallExpr() && current.asMethodCallExpr().getScope().isPresent()
                && SAME_ELEMENT_METHODS.contains(current.asMethodCallExpr().getNameAsString())) {
            passed.add(current.asMethodCallExpr());
            current = Nodes.unwrap(current.asMethodCallExpr().getScope().get());
        }
        return passed;
    }

    /**
     * @return коллекция, с которой начинается перебор: для {@code byId.values().stream().filter(...)} это
     * {@code byId}
     */
    public Optional<Expression> collectionOf(Node declaration) {
        return iteratedBy(declaration).map(iterated -> sourceOf(iterated).expression());
    }

    // Выражение, элементы которого получает лямбда - аргумент вызова
    private Optional<Expression> iteratedBy(MethodCallExpr call, int parameters) {
        String method = call.getNameAsString();
        boolean element = ELEMENT_METHODS.contains(method) && (parameters == 1 || FOR_EACH.equals(method));
        boolean comparison = COMPARING_METHODS.contains(method) && parameters == PAIR;
        if (element || comparison) {
            return call.getScope();
        }
        // stream.collect(Collectors.toMap(order -> order.getId(), order -> order))
        boolean factory = call.getScope()
                .filter(scope -> ELEMENT_FACTORIES.contains(Nodes.unwrap(scope).toString()))
                .isPresent();
        if (factory && parameters == 1 && call.getParentNode().orElse(null) instanceof MethodCallExpr consumer
                && FACTORY_CONSUMERS.contains(consumer.getNameAsString())) {
            return consumer.getScope();
        }
        return Optional.empty();
    }

    // orders.stream().filter(...) -> orders; futures.values().stream() -> значения futures
    private Source sourceOf(Expression iterated) {
        Expression current = Nodes.unwrap(iterated);
        View view = View.ITSELF;
        while (current.isMethodCallExpr() && current.asMethodCallExpr().getScope().isPresent()) {
            MethodCallExpr call = current.asMethodCallExpr();
            String method = call.getNameAsString();
            boolean ofMap = view == View.ITSELF && call.getArguments().isEmpty()
                    && (VALUES.equals(method) || KEY_SET.equals(method));
            if (ofMap) {
                view = VALUES.equals(method) ? View.VALUES : View.KEYS;
            } else if (!SAME_ELEMENT_METHODS.contains(method)) {
                break;
            }
            current = Nodes.unwrap(call.getScope().get());
        }
        return new Source(current, view);
    }

    // Тип так, как он записан в объявлении: вместе с тем, чем параметризована коллекция
    private Optional<Type> writtenType(Expression source) {
        if (source.isObjectCreationExpr()) {
            return Optional.of(source.asObjectCreationExpr().getType());
        }
        if (source.isMethodCallExpr()) {
            MethodCallExpr call = source.asMethodCallExpr();
            return Types.declaration(call)
                    .map(MethodDeclaration::getType)
                    .or(() -> Lombok.getterFieldType(call));
        }
        Optional<Node> declaration = Optional.empty();
        if (source.isNameExpr()) {
            declaration = LocalTypes.findDeclaration(source);
        } else if (source.isFieldAccessExpr() && source.asFieldAccessExpr().getScope().isThisExpr()) {
            declaration = LocalTypes.findField(source, source.asFieldAccessExpr().getNameAsString())
                    .map(Node.class::cast);
        }
        return declaration.flatMap(ElementTypes::typeOf);
    }

    private Optional<Type> typeOf(Node declaration) {
        if (declaration instanceof Parameter parameter && !parameter.getType().isUnknownType()) {
            return Optional.of(parameter.getType());
        }
        if (declaration instanceof VariableDeclarator variable && !variable.getType().isVarType()) {
            return Optional.of(variable.getType());
        }
        return Optional.empty();
    }

    /**
     * @param pairPosition какой из двух параметров map.forEach((key, value) -> ...) нужен; -1 - лямбда с одним
     *                     параметром или цикл
     */
    private Optional<Type> elementOf(Type collection, View view, int pairPosition) {
        if (collection.isArrayType()) {
            return view == View.ITSELF && pairPosition < 0
                    ? Optional.of(collection.asArrayType().getComponentType())
                    : Optional.empty();
        }
        if (!collection.isClassOrInterfaceType()) {
            return Optional.empty();
        }
        NodeList<Type> arguments = collection.asClassOrInterfaceType().getTypeArguments().orElseGet(NodeList::new);
        Optional<Type> element = Optional.empty();
        if (collection.asClassOrInterfaceType().getNameAsString().endsWith(MAP_SUFFIX)) {
            element = entryPartOf(arguments, view, pairPosition);
        } else if (arguments.size() == 1 && view == View.ITSELF && pairPosition < 0) {
            element = Optional.of(arguments.get(0));
        }
        return element.flatMap(ElementTypes::valueType).filter(type -> !isTypeVariable(type));
    }

    // Map<K, V>: значения, ключи либо один из параметров forEach((key, value) -> ...)
    private Optional<Type> entryPartOf(NodeList<Type> arguments, View view, int pairPosition) {
        if (arguments.size() != PAIR) {
            return Optional.empty();
        }
        if (view == View.VALUES) {
            return Optional.of(arguments.get(VALUE));
        }
        if (view == View.KEYS) {
            return Optional.of(arguments.get(KEY));
        }
        return pairPosition >= 0 ? Optional.of(arguments.get(pairPosition)) : Optional.empty();
    }

    // List<? extends Order> отдает Order; из List<?> и List<? super Order> тип элемента не узнать
    private Optional<Type> valueType(Type type) {
        if (!type.isWildcardType()) {
            return Optional.of(type);
        }
        return type.asWildcardType().getExtendedType().map(Type.class::cast);
    }

    // class Box<T> { List<T> items; }: T - не имя класса
    private boolean isTypeVariable(Type type) {
        if (!type.isClassOrInterfaceType() || type.asClassOrInterfaceType().getScope().isPresent()) {
            return false;
        }
        String name = type.asClassOrInterfaceType().getNameAsString();
        Node current = type.getParentNode().orElse(null);
        while (current != null) {
            NodeList<TypeParameter> declared = new NodeList<>();
            if (current instanceof ClassOrInterfaceDeclaration owner) {
                declared = owner.getTypeParameters();
            } else if (current instanceof CallableDeclaration<?> callable) {
                declared = callable.getTypeParameters();
            }
            if (declared.stream().anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // Узлы сравниваются по содержимому, а два параметра (a, a) в неверном коде совпали бы: нужен именно этот узел
    private int positionOf(NodeList<Parameter> parameters, Parameter parameter) {
        for (int index = 0; index < parameters.size(); index++) {
            if (parameters.get(index) == parameter) {
                return index;
            }
        }
        return -1;
    }
}
