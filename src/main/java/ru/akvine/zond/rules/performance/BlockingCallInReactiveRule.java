package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.HttpCalls;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Блокирующий вызов в реактивном коде: в операторе Mono / Flux либо прямо в методе, который их возвращает.
 * Такой код выполняется в потоке event loop, а их столько, сколько ядер: пока один ждет ответа, запросы
 * остальных клиентов стоят.
 */
@Component
public class BlockingCallInReactiveRule extends AbstractRule {
    private static final String REACTOR_PACKAGE = "reactor.core.publisher";
    private static final Set<String> PUBLISHERS = Set.of("Mono", "Flux");

    // Ожидание результата Mono / Flux на месте
    private static final Set<String> REACTOR_WAITS = Set.of("block", "blockFirst", "blockLast", "blockOptional");
    // Ожидание Future
    private static final Set<String> FUTURE_WAITS = Set.of("get", "join");
    private static final Set<String> FUTURE_TYPES = Set.of("Future", "CompletableFuture");
    private static final Set<String> SLEEPS = Set.of("sleep");
    private static final Set<String> SLEEP_OWNERS = Set.of("Thread", "TimeUnit");
    private static final Pattern JDBC_TEMPLATE = Pattern.compile(".*jdbcTemplate$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> JDBC_TEMPLATE_TYPES = Set.of("JdbcTemplate", "NamedParameterJdbcTemplate", "JdbcClient");

    // Операторы, которые есть только у Mono / Flux: по ним видно, что цепочка реактивная, даже если тип ее
    // начала неизвестен. map, filter и flatMap есть и у стримов, и у Optional
    private static final Set<String> REACTOR_OPERATORS = Set.of(
            "doOnNext", "doOnSuccess", "doOnError", "doOnEach", "doFinally", "doOnTerminate", "doOnSubscribe",
            "flatMapMany", "flatMapIterable", "concatMap", "switchMap", "subscribeOn", "publishOn", "switchIfEmpty",
            "defaultIfEmpty", "onErrorResume", "onErrorReturn", "onErrorMap", "thenReturn", "zipWith", "zipWhen",
            "collectList", "collectMap", "delayElements", "filterWhen", "delayUntil", "bodyToMono", "bodyToFlux",
            "exchangeToMono", "exchangeToFlux", "handle", "subscribe");

    // Операторы Mono / Flux, которые выполняют переданную лямбду в потоке цепочки
    private static final Set<String> LAMBDA_OPERATORS = Set.of(
            "map", "flatMap", "flatMapMany", "flatMapIterable", "concatMap", "switchMap", "filter", "filterWhen", "handle",
            "doOnNext", "doOnSuccess", "doOnError", "doOnEach", "doFinally", "doOnTerminate", "doOnSubscribe", "subscribe",
            "fromCallable", "fromSupplier", "fromRunnable", "defer", "create", "generate", "reduce", "zipWith", "zipWhen",
            "delayUntil", "onErrorResume", "onErrorMap", "onErrorReturn", "transform", "takeWhile", "skipWhile", "then");

    // Работа увезена в другой пул: subscribeOn(Schedulers.boundedElastic()) - так и задумано для блокирующего кода
    private static final Set<String> SCHEDULER_SWITCHES = Set.of("subscribeOn", "publishOn");

    // Операторы, лямбда которых должна вернуть Publisher: репозиторий в ней сам реактивный
    private static final Set<String> PUBLISHER_OPERATORS = Set.of(
            "flatMap", "flatMapMany", "concatMap", "switchMap", "filterWhen", "delayUntil", "zipWhen", "defer",
            "onErrorResume", "then");

    // Реактивные репозитории возвращают Mono / Flux и не блокируют
    private static final Set<String> REACTIVE_REPOSITORIES = Set.of(
            "ReactiveCrudRepository", "ReactiveSortingRepository", "R2dbcRepository", "ReactiveMongoRepository",
            "ReactiveCassandraRepository", "ReactiveElasticsearchRepository", "ReactiveNeo4jRepository",
            "ReactiveCouchbaseRepository");

    @Override
    public String code() {
        return RuleCodes.BLOCKING_CALL_IN_REACTIVE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет блокирующие вызовы (block(), Thread.sleep, JDBC, RestTemplate, репозитории JPA)"
                + " в операторах Mono / Flux и в методах, которые их возвращают";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Без Reactor реактивного кода нет: block() у WebClient в приложении на Spring MVC - обычный вызов
        boolean usesReactor = sourceFile.unit().getImports().stream()
                .anyMatch(declaration -> declaration.getNameAsString().startsWith(REACTOR_PACKAGE));
        if (!usesReactor) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<String> blocking = describeBlocking(call);
            if (blocking.isEmpty() || TestClasses.isInside(call) || CodeContexts.isStartup(call)) {
                continue;
            }
            Optional<String> context = reactiveContext(call);
            if (context.isPresent()) {
                violations.add(violation(sourceFile, call,
                        "Блокирующий вызов '" + blocking.get() + "' " + context.get() + ": код выполняется в потоке"
                                + " event loop, а их столько, сколько ядер, - пока поток ждет, запросы остальных"
                                + " клиентов стоят; используйте неблокирующий вариант (WebClient, R2DBC, реактивный"
                                + " репозиторий) либо Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private Optional<String> describeBlocking(MethodCallExpr call) {
        String name = call.getNameAsString();
        Optional<Expression> scope = call.getScope();
        if (REACTOR_WAITS.contains(name) && call.getArguments().size() <= 1) {
            return Optional.of(name + "()");
        }
        if (SLEEPS.contains(name) && scope.filter(owner -> SLEEP_OWNERS.contains(rootName(owner))).isPresent()) {
            return Optional.of(scope.get() + "." + name);
        }
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        if (FUTURE_WAITS.contains(name) && Types.isKindOf(scope.get(), FUTURE_TYPES).orElse(false)) {
            return Optional.of(MethodCalls.receiverName(scope.get()) + "." + name);
        }
        if (isJdbcTemplate(scope.get())) {
            return Optional.of(MethodCalls.receiverName(scope.get()) + "." + name);
        }
        if (Repositories.isRepositoryCall(call) && !isReactiveRepository(call)) {
            return Optional.of(MethodCalls.receiverName(scope.get()) + "." + name);
        }
        // WebClient сам неблокирующий: блокирует его только block(), о котором выше
        return HttpCalls.describeKnownClient(call).filter(described -> !isWebClientChain(call));
    }

    /**
     * Где вызов попадает в поток event loop: в лямбде оператора реактивной цепочки, которую не увезли в другой
     * пул, либо прямо в теле метода, который возвращает Mono / Flux, - он выполняется при сборке цепочки
     */
    private Optional<String> reactiveContext(MethodCallExpr call) {
        Optional<LambdaExpr> lambda = call.findAncestor(LambdaExpr.class)
                .filter(found -> Nodes.enclosingCallable(found).orElse(null) == Nodes.enclosingCallable(call).orElse(null));
        if (lambda.isPresent()) {
            Optional<MethodCallExpr> operator = lambda.get().getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr)
                    .map(parent -> (MethodCallExpr) parent);
            // tbAiService.processStream(chatId, (client, token) -> ...): лямбду получил свой метод, когда и где он
            // ее выполнит, неизвестно
            if (operator.isEmpty() || !LAMBDA_OPERATORS.contains(operator.get().getNameAsString())
                    || !isReactiveChain(operator.get()) || isOffloaded(operator.get())) {
                return Optional.empty();
            }
            if (PUBLISHER_OPERATORS.contains(operator.get().getNameAsString()) && returnsFromLambda(call, lambda.get())
                    && !REACTOR_WAITS.contains(call.getNameAsString())) {
                return Optional.empty();
            }
            return Optional.of("в операторе '" + operator.get().getNameAsString() + "' реактивной цепочки");
        }
        return Nodes.enclosingCallable(call)
                .filter(callable -> callable instanceof MethodDeclaration)
                .map(callable -> (MethodDeclaration) callable)
                .filter(method -> PUBLISHERS.contains(LocalTypes.typeName(method.getType())))
                .filter(method -> !isOffloaded(call))
                .map(method -> "в методе '" + method.getNameAsString() + "', который возвращает "
                        + LocalTypes.typeName(method.getType()));
    }

    // Цепочка реактивная: начинается с Mono. / Flux., содержит оператор, который есть только у них, начинается
    // с переменной типа Mono / Flux либо возвращается из метода, который возвращает Mono / Flux
    private boolean isReactiveChain(MethodCallExpr operator) {
        for (MethodCallExpr step : chain(operator)) {
            if (REACTOR_OPERATORS.contains(step.getNameAsString())) {
                return true;
            }
            Optional<Expression> scope = step.getScope().map(Nodes::unwrap);
            if (scope.isPresent() && scope.get().isNameExpr() && isPublisherName(scope.get())) {
                return true;
            }
        }
        MethodCallExpr outermost = outermost(operator);
        return outermost.getParentNode().filter(parent -> parent instanceof ReturnStmt).isPresent()
                && outermost.findAncestor(MethodDeclaration.class)
                .filter(method -> PUBLISHERS.contains(LocalTypes.typeName(method.getType())))
                .isPresent();
    }

    // Mono.just(...) либо переменная, объявленная как Mono / Flux
    private boolean isPublisherName(Expression scope) {
        String name = scope.asNameExpr().getNameAsString();
        if (PUBLISHERS.contains(name)) {
            return true;
        }
        return LocalTypes.findDeclaration(scope)
                .flatMap(LocalTypes::declaredType)
                .filter(PUBLISHERS::contains)
                .isPresent();
    }

    private boolean isOffloaded(MethodCallExpr call) {
        return chain(call).stream().anyMatch(step -> SCHEDULER_SWITCHES.contains(step.getNameAsString()));
    }

    // Все вызовы цепочки, в которой стоит этот: вглубь по объектам вызова и наружу по вызовам над ним
    private List<MethodCallExpr> chain(MethodCallExpr call) {
        List<MethodCallExpr> steps = new ArrayList<>();
        Expression current = outermost(call);
        while (current instanceof MethodCallExpr step) {
            steps.add(step);
            current = step.getScope().map(Nodes::unwrap).orElse(null);
        }
        return steps;
    }

    private MethodCallExpr outermost(MethodCallExpr call) {
        MethodCallExpr current = call;
        while (current.getParentNode().orElse(null) instanceof MethodCallExpr outer
                && outer.getScope().orElse(null) == current) {
            current = outer;
        }
        return current;
    }

    // id -> repository.findById(id): результат вызова и есть результат лямбды
    private boolean returnsFromLambda(MethodCallExpr call, LambdaExpr lambda) {
        MethodCallExpr outermost = outermost(call);
        Optional<Expression> body = lambda.getExpressionBody();
        if (body.isPresent()) {
            return Nodes.unwrap(body.get()) == outermost;
        }
        return outermost.getParentNode().filter(parent -> parent instanceof ReturnStmt).isPresent();
    }

    private boolean isReactiveRepository(MethodCallExpr call) {
        return call.getScope().map(Types::hierarchy)
                .filter(names -> names.stream().anyMatch(REACTIVE_REPOSITORIES::contains))
                .isPresent();
    }

    private boolean isJdbcTemplate(Expression scope) {
        return Types.matches(scope, JDBC_TEMPLATE_TYPES::contains)
                .orElseGet(() -> JDBC_TEMPLATE.matcher(MethodCalls.receiverName(scope)).matches());
    }

    private boolean isWebClientChain(MethodCallExpr call) {
        return chain(call).stream().anyMatch(step -> REACTOR_OPERATORS.contains(step.getNameAsString()))
                || MethodCalls.receiverName(call.getScope().get()).toLowerCase().contains("webclient");
    }

    // Thread.sleep -> Thread, TimeUnit.SECONDS.sleep -> TimeUnit
    private String rootName(Expression scope) {
        Node current = Nodes.unwrap(scope);
        while (current instanceof FieldAccessExpr access) {
            current = access.getScope();
        }
        return current instanceof NameExpr name ? name.getNameAsString() : "";
    }
}
