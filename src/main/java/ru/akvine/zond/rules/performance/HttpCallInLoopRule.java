package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallChains;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.HttpCalls;
import ru.akvine.zond.rules.support.Lifecycle;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP-запрос на каждый элемент цикла: N+1 по сети. Сетевой вызов дороже запроса к БД, а внешний сервис
 * к тому же может ограничивать частоту запросов.
 */
@Component
public class HttpCallInLoopRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь от цикла искать HTTP-вызов; 0 - не искать");

    // Асинхронный ответ WebClient: Flux.fromIterable(ids).flatMap(id -> webClient...bodyToMono(...)) отправляет
    // запросы одновременно, и это осознанный выбор. Синхронным его делает только block()
    private static final Set<String> ASYNC_RESULTS = Set.of("bodyToMono", "bodyToFlux", "exchangeToMono", "exchangeToFlux");
    private static final Set<String> BLOCKING_WAITS = Set.of("block", "blockFirst", "blockLast", "blockOptional");
    private static final Set<String> CONFIGURATION_ANNOTATIONS = Set.of(
            "Configuration", "EnableWebSecurity", "EnableAuthorizationServer", "EnableResourceServer");
    // Ответ придет в обработчик: client.execute(action, request, listener) в Elasticsearch, sendAsync, enqueue.
    // Цикл ответа не ждет - запросы уходят одновременно
    private static final Pattern LISTENER = Pattern.compile("listener", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASYNC_METHOD = Pattern.compile(".*Async$|^enqueue$");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public String code() {
        return RuleCodes.HTTP_CALL_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет HTTP-вызовы в цикле и в поэлементных операциях стримов,"
                + " в том числе спрятанные в методах, которые вызываются из цикла";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        Lifecycle lifecycle = Lifecycle.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            // webClient.get().uri(...).retrieve() - один запрос, а не три
            Set<Integer> reportedLines = new HashSet<>();
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                // Цикл повторных попыток повторяет один запрос, catch выполняется только при сбое, а вызов,
                // после которого цикл кончается, уходит один раз
                if (!CodeContexts.isRepeatedOverData(call) || CodeContexts.isInCatch(call) || Loops.endsIteration(call)
                        || isAsyncResponse(call) || isConfigurationCode(call)) {
                    continue;
                }
                describe(call, graph)
                        .filter(problem -> !lifecycle.isAtStartupOrShutdown(call))
                        .filter(problem -> reportedLines.add(call.getBegin().map(position -> position.line).orElse(0)))
                        .ifPresent(problem -> violations.add(violation(sourceFile, call, problem.text()
                                + ": на каждый элемент уходит отдельный сетевой запрос, и время цикла растет вместе"
                                + " с числом элементов; передайте все элементы одним запросом, если API это умеет,"
                                + " либо отправляйте запросы параллельно с ограничением числа одновременных")
                                .withConfidence(problem.confidence())));
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

    /**
     * @param confidence вызов стоит прямо в цикле - это видно в коде; путь через вызовы установлен точно, только
     *                   если каждый вызов в нем найден по типам
     */
    private record Problem(String text, Confidence confidence) {
    }

    private Optional<Problem> describe(MethodCallExpr call, CallGraph graph) {
        Optional<String> direct = describeBlocking(call);
        if (direct.isPresent()) {
            return Optional.of(new Problem("Сетевой вызов '" + direct.get() + "' в цикле", Confidence.CONFIRMED));
        }

        // enrich(order) в цикле, а запрос к внешнему сервису - внутри enrich или еще глубже
        for (MethodDeclaration target : graph.targetsOf(call)) {
            Optional<CallChains.Found> found = CallChains.find(
                    graph, target, value(MAX_CALL_DEPTH), method -> false, HttpCallInLoopRule::describeBlocking);
            if (found.isPresent()) {
                return Optional.of(new Problem(
                        "Вызов '" + call.getNameAsString() + "(...)' в цикле делает сетевой вызов '"
                                + found.get().operation() + "' (через вызов " + found.get().chain() + ")",
                        found.get().confidence(graph.isExact(call))));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> describeBlocking(Node node) {
        return node instanceof MethodCallExpr call && isAnsweredByListener(call) ? Optional.empty() : HttpCalls.describe(node);
    }

    // projectClient.admin().indices().rolloverIndex(request, listener): обработчик стоит в любом звене цепочки
    private static boolean isAnsweredByListener(MethodCallExpr call) {
        Node current = call;
        while (current instanceof MethodCallExpr step) {
            if (ASYNC_METHOD.matcher(step.getNameAsString()).matches()
                    || step.getArguments().stream().anyMatch(argument -> LISTENER.matcher(argument.toString()).find())) {
                return true;
            }
            current = step.getParentNode().filter(parent -> parent instanceof MethodCallExpr).orElse(null);
        }
        return false;
    }

    // Класс настроек выполняется при запуске: clients.inMemory().withClient(id).additionalInformation(...) -
    // построитель настроек, а не запрос. Лямбды в нем (например, тело @Bean-задачи) выполнятся позже
    private boolean isConfigurationCode(MethodCallExpr call) {
        return call.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> Annotations.hasAny(type, CONFIGURATION_ANNOTATIONS))
                .isPresent()
                && Nodes.enclosingCallable(call).filter(callable -> !Nodes.isInNestedScope(call, callable)).isPresent();
    }

    // Цепочка WebClient, которая возвращает Mono или Flux и не ждет ответа на месте
    private boolean isAsyncResponse(MethodCallExpr call) {
        boolean async = false;
        Node current = call;
        while (current instanceof MethodCallExpr step) {
            if (BLOCKING_WAITS.contains(step.getNameAsString())) {
                return false;
            }
            async |= ASYNC_RESULTS.contains(step.getNameAsString());
            current = step.getParentNode().filter(parent -> parent instanceof MethodCallExpr).orElse(null);
        }
        return async;
    }
}
