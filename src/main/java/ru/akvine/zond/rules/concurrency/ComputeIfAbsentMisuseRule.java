package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallChains;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.HttpCalls;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Repositories;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Функция в computeIfAbsent, compute и merge выполняется, пока карта держит блокировку. Менять в ней ту же
 * карту нельзя: ConcurrentHashMap отвечает IllegalStateException (Recursive update) либо зависает, HashMap -
 * ConcurrentModificationException. А долгая работа в ней - запрос по сети или в базу - держит блокировку корзины,
 * и потоки с другими ключами из той же корзины ждут.
 */
@Component
public class ComputeIfAbsentMisuseRule extends AbstractRule implements ProjectRule {
    private static final Set<String> COMPUTING = Set.of("computeIfAbsent", "computeIfPresent", "compute", "merge");
    private static final Set<String> CHANGES = Set.of(
            "put", "putIfAbsent", "putAll", "remove", "clear", "computeIfAbsent", "computeIfPresent", "compute",
            "merge", "replace");
    private static final Set<String> CONCURRENT_MAPS = Set.of("ConcurrentHashMap", "ConcurrentMap");
    private static final String SLEEP = "sleep";
    // На сколько вызовов вглубь от функции искать блокирующую операцию
    private static final int CALL_DEPTH = 3;

    @Override
    public String code() {
        return RuleCodes.COMPUTE_IF_ABSENT_MISUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет функции в computeIfAbsent, compute и merge, которые меняют ту же карту"
                + " либо ходят в сеть и в базу под блокировкой ConcurrentHashMap";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (!COMPUTING.contains(call.getNameAsString()) || call.getArguments().size() < 2 || call.getScope().isEmpty()) {
                    continue;
                }
                Expression map = Nodes.unwrap(call.getScope().get());
                Expression function = Nodes.unwrap(call.getArgument(call.getArguments().size() - 1));
                Optional<String> change = describeChange(call, map, function);
                if (change.isPresent()) {
                    violations.add(violation(sourceFile, call.getName(),
                            "Функция в '" + Nodes.text(map) + "." + call.getNameAsString() + "(...)' " + change.get()
                                    + ": карта в этот момент уже меняется. ConcurrentHashMap ответит"
                                    + " IllegalStateException (Recursive update) либо зависнет, HashMap -"
                                    + " ConcurrentModificationException; вычислите значение до вызова, а в карту"
                                    + " кладите готовый результат"));
                    continue;
                }
                if (isConcurrent(map)) {
                    describeBlocking(function, classes, graph).ifPresent(blocking -> violations.add(violation(
                            sourceFile, call.getName(),
                            "Функция в '" + Nodes.text(map) + "." + call.getNameAsString() + "(...)' выполняет '"
                                    + blocking + "' под блокировкой карты: пока идет запрос, потоки с другими"
                                    + " ключами из той же корзины ждут, а при зависшем вызове ждут бесконечно;"
                                    + " функция должна быть короткой. Загружайте значение вне карты либо возьмите"
                                    + " кеш, который грузит записи без общей блокировки (Caffeine)")
                            .withLevel(ErrorLevel.MINOR)));
                }
            }
        }
        return violations;
    }

    // Строже всех находок - изменение карты из ее же функции: это исключение либо зависание
    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    /**
     * @return как функция меняет карту, для которой вызвана: прямым вызовом либо рекурсией через свой же метод
     */
    private Optional<String> describeChange(MethodCallExpr compute, Expression map, Expression function) {
        String name = Nodes.text(map);
        for (MethodCallExpr inner : function.findAll(MethodCallExpr.class)) {
            boolean sameMap = inner.getScope().filter(scope -> Nodes.text(Nodes.unwrap(scope)).equals(name)).isPresent();
            if (sameMap && CHANGES.contains(inner.getNameAsString())) {
                return Optional.of("меняет ту же карту ('" + Nodes.text(inner) + "')");
            }
        }
        // cache.computeIfAbsent(n, k -> fib(k - 1) + fib(k - 2)) внутри fib: рекурсия снова придет в эту карту
        Optional<MethodDeclaration> owner = compute.findAncestor(MethodDeclaration.class);
        return owner.flatMap(method -> function.findAll(MethodCallExpr.class).stream()
                .filter(inner -> inner.getNameAsString().equals(method.getNameAsString()))
                .filter(inner -> inner.getScope().map(Nodes::unwrap).filter(scope -> !scope.isThisExpr()).isEmpty())
                .filter(inner -> inner.getArguments().size() == method.getParameters().size())
                .findFirst()
                .map(inner -> "рекурсивно вызывает '" + method.getNameAsString() + "(...)', который снова"
                        + " обращается к этой карте"));
    }

    private boolean isConcurrent(Expression map) {
        boolean declared = LocalTypes.typeOf(map).filter(CONCURRENT_MAPS::contains).isPresent();
        return declared || LocalTypes.findInitializer(map)
                .map(Nodes::unwrap)
                .filter(Expression::isObjectCreationExpr)
                .filter(creation -> CONCURRENT_MAPS.contains(creation.asObjectCreationExpr().getType().getNameAsString()))
                .isPresent();
    }

    // Запрос по сети, обращение к репозиторию, пауза - в лямбде, по ссылке на метод либо в методе, который вызывают
    private Optional<String> describeBlocking(Expression function, ProjectClasses classes, CallGraph graph) {
        if (function.isMethodReferenceExpr()) {
            boolean repository = Repositories.isRepositoryReference(function.asMethodReferenceExpr());
            return repository
                    ? Optional.of(Nodes.text(function))
                    : HttpCalls.describeReference(function.asMethodReferenceExpr(), classes);
        }
        for (MethodCallExpr inner : function.findAll(MethodCallExpr.class)) {
            Optional<String> blocking = blockingOperation(inner).or(() -> graph.targetsOf(inner).stream()
                    .map(target -> CallChains.find(graph, target, CALL_DEPTH, skipped -> false, this::blockingOperation))
                    .flatMap(Optional::stream)
                    .map(CallChains.Found::operation)
                    .findFirst());
            if (blocking.isPresent()) {
                return blocking;
            }
        }
        return Optional.empty();
    }

    private Optional<String> blockingOperation(Node node) {
        Optional<String> http = HttpCalls.describeKnownClient(node);
        if (http.isPresent()) {
            return http;
        }
        boolean storage = node instanceof MethodCallExpr call
                && (Repositories.isRepositoryCall(call) || SLEEP.equals(call.getNameAsString()) && call.getScope().isPresent());
        return storage
                ? Optional.of(((MethodCallExpr) node).getScope().map(scope -> Nodes.text(scope) + ".").orElse("")
                + ((MethodCallExpr) node).getNameAsString())
                : Optional.empty();
    }
}
