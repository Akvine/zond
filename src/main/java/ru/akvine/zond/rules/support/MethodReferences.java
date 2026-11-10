package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ссылки на методы в поэлементных операциях: {@code ids.stream().map(priceClient::fetch)} вызывает fetch для
 * каждого элемента так же, как лямбда {@code id -> priceClient.fetch(id)}, но вызова в коде при этом нет
 */
@UtilityClass
public class MethodReferences {
    // Операции, которых у Optional нет: переданная функция вызывается для каждого элемента
    private final static Set<String> PER_ELEMENT = Set.of(
            "forEach", "forEachOrdered", "peek", "anyMatch", "allMatch", "noneMatch", "mapToInt", "mapToLong",
            "mapToDouble", "mapToObj", "removeIf", "replaceAll");
    // Есть и у стрима, и у Optional: поэлементными их делает только стрим перед ними
    private final static Set<String> SHARED_WITH_OPTIONAL = Set.of("map", "flatMap", "filter");
    private final static Set<String> STREAM_STEPS = Set.of(
            "stream", "parallelStream", "parallel", "sequential", "boxed", "lines", "chars", "sorted", "distinct",
            "limit", "skip", "peek", "mapToObj", "mapToInt", "mapToLong", "mapToDouble", "range", "rangeClosed");
    private final static Set<String> STREAM_TYPES = Set.of("Stream", "IntStream", "LongStream", "DoubleStream");

    /**
     * @return операция, которая вызывает метод по ссылке для каждого элемента: map в {@code stream().map(a::b)}
     */
    public Optional<MethodCallExpr> perElementOperation(MethodReferenceExpr reference) {
        return reference.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr)
                .map(parent -> (MethodCallExpr) parent)
                .filter(operation -> operation.getArguments().stream().anyMatch(argument -> argument == reference))
                .filter(operation -> PER_ELEMENT.contains(operation.getNameAsString())
                        || SHARED_WITH_OPTIONAL.contains(operation.getNameAsString()) && isOnStream(operation));
    }

    /**
     * @return методы проекта, на которые указывает ссылка: для {@code priceClient::fetch} - метод fetch класса,
     * которым объявлено поле priceClient, а если это интерфейс - его реализации. Пусто, если метод перегружен:
     * какую из перегрузок выберет компилятор, по одной ссылке не понять
     */
    public List<MethodDeclaration> targets(MethodReferenceExpr reference, ProjectClasses classes) {
        Optional<ClassOrInterfaceDeclaration> owner = ownerOf(reference, classes);
        if (owner.isEmpty()) {
            return List.of();
        }
        List<MethodDeclaration> named = owner.get().getMethodsByName(reference.getIdentifier());
        if (named.size() != 1) {
            return List.of();
        }
        if (named.get(0).getBody().isPresent()) {
            return named;
        }
        String contract = owner.get().getNameAsString();
        return classes.all().stream()
                .filter(type -> !type.isInterface())
                .filter(type -> type.getImplementedTypes().stream()
                        .anyMatch(implemented -> implemented.getNameAsString().equals(contract)))
                .flatMap(type -> type.getMethodsByName(reference.getIdentifier()).stream())
                .filter(method -> method.getBody().isPresent())
                .toList();
    }

    /**
     * @return класс проекта, метод которого вызывает ссылка
     */
    public Optional<ClassOrInterfaceDeclaration> ownerOf(MethodReferenceExpr reference, ProjectClasses classes) {
        Expression scope = Nodes.unwrap(reference.getScope());
        if (scope.isThisExpr()) {
            return reference.findAncestor(ClassOrInterfaceDeclaration.class);
        }
        return scopeType(reference).flatMap(classes::find);
    }

    /**
     * @return простое имя типа, чей метод вызывает ссылка: тип переменной либо сам класс в {@code Type::method}
     */
    public Optional<String> scopeType(MethodReferenceExpr reference) {
        String name = scopeName(reference);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> declared = LocalTypes.findDeclaration(reference, name).flatMap(LocalTypes::declaredType);
        if (declared.isPresent()) {
            return declared;
        }
        return Character.isUpperCase(name.charAt(0)) ? Optional.of(name) : Optional.empty();
    }

    /**
     * @return имя переменной или поля, на котором вызывается метод: priceClient для {@code priceClient::fetch};
     * пустая строка для {@code Type::method} и для ссылок на результат выражения
     */
    public String receiverName(MethodReferenceExpr reference) {
        String name = scopeName(reference);
        boolean variable = !name.isEmpty()
                && (Character.isLowerCase(name.charAt(0)) || LocalTypes.findDeclaration(reference, name).isPresent());
        return variable ? name : "";
    }

    // Парсер не знает, переменная перед :: или класс, и то и другое отдает как тип
    private String scopeName(MethodReferenceExpr reference) {
        Expression scope = Nodes.unwrap(reference.getScope());
        if (scope.isTypeExpr()) {
            // System.out::println - составное имя: это не поле текущего класса
            String text = scope.asTypeExpr().getType().asString();
            return text.contains(".") || text.contains("<") ? "" : text;
        }
        if (scope.isNameExpr()) {
            return scope.asNameExpr().getNameAsString();
        }
        return scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr()
                ? scope.asFieldAccessExpr().getNameAsString()
                : "";
    }

    private boolean isOnStream(MethodCallExpr operation) {
        boolean afterStreamStep = StreamChains.callsBefore(operation).stream()
                .anyMatch(step -> STREAM_STEPS.contains(step.getNameAsString())
                        || step.getScope().filter(scope -> STREAM_TYPES.contains(scope.toString())).isPresent());
        return afterStreamStep || operation.getScope()
                .flatMap(LocalTypes::typeOf)
                .filter(STREAM_TYPES::contains)
                .isPresent();
    }
}
