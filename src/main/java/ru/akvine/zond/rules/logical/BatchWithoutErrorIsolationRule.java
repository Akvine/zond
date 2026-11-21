package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallChains;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.HttpCalls;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Messaging;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.TransactionalAnnotations;
import ru.akvine.zond.rules.support.Types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Задача по расписанию перебирает записи из базы и для каждой обращается к внешней системе, а обработка одной
 * записи в try-catch не взята. Первый же сбой - сеть моргнула, сервис ответил ошибкой на одну запись - обрывает
 * весь проход: до остальных записей очередь не доходит. При следующем запуске задача начинает с той же записи
 * и падает на ней снова, так что одна негодная запись останавливает обработку всех, кто стоит за ней.
 * <p>
 * Try-catch вокруг всего цикла здесь не помогает: проход он так же заканчивает на первой ошибке
 */
@Component
public class BatchWithoutErrorIsolationRule extends AbstractRule implements ProjectRule {
    private static final Set<String> JOB_ANNOTATIONS = Set.of("Scheduled", "Schedules", "XxlJob", "Recurring");
    // Quartz: execute(JobExecutionContext) и executeInternal(JobExecutionContext)
    private static final Set<String> QUARTZ_METHODS = Set.of("execute", "executeInternal");
    private static final String QUARTZ_CONTEXT = "JobExecutionContext";
    private static final String ASYNC = "Async";
    private static final String FOR_EACH = "forEach";
    private static final String QUERY = "Query";
    // Отказ принимает запасной метод: наружу исключение не выходит
    private static final Set<String> FALLBACK_MEMBERS = Set.of("fallbackMethod", "fallback", "recover");
    private static final String RECOVER = "Recover";

    // Между источником записей и перебором: adverts.stream().filter(...).toList(), page.getContent()
    private static final Set<String> PASS_THROUGH = Set.of(
            "stream", "filter", "sorted", "distinct", "limit", "skip", "peek", "map", "toList", "collect",
            "getContent", "toSet", "values", "copyOf", "unmodifiableList", "newArrayList");
    // Записи читают по порядку: остановиться на первой ошибке могли намеренно, чтобы не нарушить очередность
    private static final Pattern ORDERED_QUERY = Pattern.compile(".*(OrderBy|Ordered|Sorted|InOrder).*");
    private static final Pattern ORDER_BY = Pattern.compile("(?is).*\\border\\s+by\\b.*");

    private static final Set<String> MAIL_SENDERS = Set.of("JavaMailSender", "MailSender", "JavaMailSenderImpl");
    private static final Pattern MAIL_SENDER_NAME = Pattern.compile("(?i).*mailSender$");
    private static final String SEND = "send";
    // url.openStream(): адрес может вести и к файлу из своих ресурсов
    private static final Pattern URL_OPENING = Pattern.compile("\\.open(Stream|Connection)$");

    private static final int MAX_SOURCE_HOPS = 6;
    private static final int SOURCE_DEPTH = 2;
    private static final int REMOTE_DEPTH = 3;
    // Сколько вызовов от задачи до метода с циклом: run() -> service.processAll()
    private static final int PASS_DEPTH = 2;

    private record Iteration(Node node, Expression source, Node body) {
    }

    private record Remote(String operation, String chain) {
    }

    @Override
    public String code() {
        return RuleCodes.BATCH_WITHOUT_ERROR_ISOLATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет задачи по расписанию, в которых сбой внешнего вызова на одной записи"
                + " обрывает обработку остальных";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        Map<CompilationUnit, SourceFile> files = new IdentityHashMap<>();
        sourceFiles.forEach(sourceFile -> files.put(sourceFile.unit(), sourceFile));
        Set<Node> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration job : sourceFile.findAll(MethodDeclaration.class)) {
                if (!isJob(job) || TestClasses.isInside(job) || isOneTransaction(job)) {
                    continue;
                }
                for (MethodDeclaration method : passMethods(job, graph)) {
                    SourceFile owner = method.findCompilationUnit().map(files::get).orElse(null);
                    if (owner == null || method != job && isOneTransaction(method)) {
                        continue;
                    }
                    for (Iteration iteration : iterationsOf(method)) {
                        if (reported.contains(iteration.node())) {
                            continue;
                        }
                        check(job, method, iteration, graph).ifPresent(problem -> {
                            reported.add(iteration.node());
                            violations.add(violation(owner, iteration.node(), problem.message())
                                    .withConfidence(problem.confidence()));
                        });
                    }
                }
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
        return ErrorType.LOGICAL;
    }

    private record Problem(String message, Confidence confidence) {
    }

    private Optional<Problem> check(MethodDeclaration job, MethodDeclaration method, Iteration iteration,
                                    CallGraph graph) {
        Optional<MethodCallExpr> source = recordSource(iteration.source(), graph);
        Optional<Remote> remote = source.flatMap(found -> remoteCallIn(method, iteration, graph));
        if (remote.isEmpty()) {
            return Optional.empty();
        }
        String place = method == job ? "" : " (цикл в методе '" + method.getNameAsString() + "', который она вызывает)";
        String through = remote.get().chain().isEmpty() ? "" : " (через вызов " + remote.get().chain() + ")";
        String message = "Задача по расписанию '" + job.getNameAsString() + "'" + place + " перебирает записи из '"
                + describe(source.get()) + "' и для каждой делает внешний вызов '" + remote.get().operation() + "'"
                + through + ", а обработка одной записи в try-catch не взята: сбой на одной записи обрывает проход -"
                + " до остальных очередь не дойдет, а при следующем запуске задача споткнется о ту же запись;"
                + " оберните обработку одной записи в try-catch, запишите ошибку и переходите к следующей";
        return Optional.of(new Problem(message, isOrdered(source.get()) ? Confidence.SUSPICION : Confidence.PROBABLE));
    }

    private boolean isJob(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return false;
        }
        boolean quartz = QUARTZ_METHODS.contains(method.getNameAsString()) && method.getParameters().size() == 1
                && QUARTZ_CONTEXT.equals(LocalTypes.typeName(method.getParameter(0).getType()));
        return quartz || Annotations.hasAny(method, JOB_ANNOTATIONS);
    }

    // Весь проход - одна транзакция: сбой откатывает все записи разом, и остановка на первой ошибке здесь задумана
    private boolean isOneTransaction(MethodDeclaration method) {
        return TransactionalAnnotations.findEffective(method).isPresent();
    }

    /**
     * @return сама задача и методы, которые она вызывает один раз за проход: цикл по записям часто стоит не в
     * методе с @Scheduled, а в сервисе, которому тот отдает работу. Вызовы из цикла сюда не входят - там перебор
     * записей уже идет уровнем выше
     */
    private List<MethodDeclaration> passMethods(MethodDeclaration job, CallGraph graph) {
        List<MethodDeclaration> methods = new ArrayList<>(List.of(job));
        collect(job, graph, PASS_DEPTH, methods);
        return methods;
    }

    private void collect(MethodDeclaration method, CallGraph graph, int depth, List<MethodDeclaration> methods) {
        if (depth == 0) {
            return;
        }
        for (CallGraph.Call call : graph.callsFrom(method)) {
            MethodDeclaration target = call.target();
            boolean oncePerPass = Loops.enclosingIteration(call.site()).isEmpty()
                    && call.site().findAncestor(LambdaExpr.class).isEmpty();
            boolean known = graph.targetsOf(call.site()).size() == 1 && target.getBody().isPresent();
            if (oncePerPass && known && !Annotations.has(target, ASYNC) && !isJob(target)
                    && methods.stream().noneMatch(seen -> seen == target)) {
                methods.add(target);
                collect(target, graph, depth - 1, methods);
            }
        }
    }

    // Перебор верхнего уровня: for (X x : items) либо items.forEach(x -> ...), не вложенный в другой перебор
    private List<Iteration> iterationsOf(MethodDeclaration method) {
        List<Iteration> found = new ArrayList<>();
        for (ForEachStmt loop : method.findAll(ForEachStmt.class)) {
            if (isOwnTopLevel(loop, method)) {
                found.add(new Iteration(loop, loop.getIterable(), loop.getBody()));
            }
        }
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            boolean perElement = FOR_EACH.equals(call.getNameAsString()) && call.getScope().isPresent()
                    && call.getArguments().size() == 1 && call.getArgument(0).isLambdaExpr();
            if (perElement && isOwnTopLevel(call, method)) {
                found.add(new Iteration(call, call.getScope().get(), call.getArgument(0).asLambdaExpr().getBody()));
            }
        }
        return found;
    }

    private boolean isOwnTopLevel(Node node, MethodDeclaration method) {
        return Nodes.enclosingCallable(node).filter(owner -> owner == method).isPresent()
                && node.findAncestor(LambdaExpr.class).isEmpty()
                && Loops.enclosingIteration(node).isEmpty();
    }

    /**
     * @return запрос, которым получены перебираемые записи: вызов репозитория либо своего метода, который до
     * репозитория доходит; пусто, если перебирают что-то другое
     */
    private Optional<MethodCallExpr> recordSource(Expression iterable, CallGraph graph) {
        Expression current = Nodes.unwrap(iterable);
        for (int hop = 0; hop < MAX_SOURCE_HOPS; hop++) {
            if (current.isNameExpr()) {
                // List<Advert> adverts = advertRepository.findByStatus(...): идем к значению переменной
                Optional<Expression> initializer = LocalTypes.isLocal(current, current.asNameExpr().getNameAsString())
                        ? LocalTypes.findInitializer(current)
                        : Optional.empty();
                if (initializer.isEmpty()) {
                    return Optional.empty();
                }
                current = Nodes.unwrap(initializer.get());
                continue;
            }
            // new ArrayList<>(adverts)
            if (current.isObjectCreationExpr() && current.asObjectCreationExpr().getArguments().size() == 1) {
                current = Nodes.unwrap(current.asObjectCreationExpr().getArgument(0));
                continue;
            }
            if (!current.isMethodCallExpr()) {
                return Optional.empty();
            }
            MethodCallExpr call = current.asMethodCallExpr();
            if (Repositories.isRepositoryCall(call) || readsRepository(call, graph)) {
                return Optional.of(call);
            }
            if (!PASS_THROUGH.contains(call.getNameAsString())) {
                return Optional.empty();
            }
            // List.copyOf(adverts) - к аргументу, adverts.stream() - к получателю
            Optional<Expression> next = call.getScope().filter(scope -> !isTypeName(scope))
                    .or(() -> call.getArguments().size() == 1 ? Optional.of(call.getArgument(0)) : Optional.empty());
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = Nodes.unwrap(next.get());
        }
        return Optional.empty();
    }

    private boolean isTypeName(Expression scope) {
        return scope.isNameExpr() && Character.isUpperCase(scope.asNameExpr().getNameAsString().charAt(0));
    }

    // advertService.findRunning(), внутри которого запрос к репозиторию
    private boolean readsRepository(MethodCallExpr call, CallGraph graph) {
        return graph.targetsOf(call).stream()
                .anyMatch(target -> CallChains.find(graph, target, SOURCE_DEPTH, method -> false,
                        node -> node instanceof MethodCallExpr query && Repositories.isRepositoryCall(query)
                                ? Optional.of(query.getNameAsString())
                                : Optional.<String>empty()).isPresent());
    }

    private boolean isOrdered(MethodCallExpr source) {
        if (ORDERED_QUERY.matcher(source.getNameAsString()).matches()) {
            return true;
        }
        return Types.declaration(source)
                .flatMap(declaration -> Annotations.find(declaration, QUERY))
                .filter(query -> ORDER_BY.matcher(query.toString()).matches())
                .isPresent();
    }

    /**
     * @return внешний вызов в теле перебора, отказ которого дойдет до самого цикла: сам вызов либо вызов своего
     * метода, который к нему ведет
     */
    private Optional<Remote> remoteCallIn(MethodDeclaration method, Iteration iteration, CallGraph graph) {
        for (MethodCallExpr call : iteration.body().findAll(MethodCallExpr.class)) {
            if (isDeferred(call, iteration.body()) || isHandledWithin(call, iteration.body())) {
                continue;
            }
            Optional<String> direct = describeRemote(call);
            if (direct.isPresent()) {
                return Optional.of(new Remote(direct.get(), ""));
            }
        }
        for (CallGraph.Call call : graph.callsFrom(method)) {
            MethodCallExpr site = call.site();
            if (!Nodes.isInside(site, iteration.body()) || isDeferred(site, iteration.body())
                    || isHandledWithin(site, iteration.body())) {
                continue;
            }
            Optional<CallChains.Found> found = CallChains.find(
                    graph, call.target(), REMOTE_DEPTH, this::absorbsFailure, this::describeEscapingRemote);
            if (found.isPresent()) {
                return Optional.of(new Remote(found.get().operation(), found.get().chain()));
            }
        }
        return Optional.empty();
    }

    // Лямбда или анонимный класс внутри тела: код может выполниться позже и в другом потоке, и его отказ до цикла
    // не дойдет
    private boolean isDeferred(Node node, Node body) {
        for (Node current = node.getParentNode().orElse(null); current != null && current != body;
             current = current.getParentNode().orElse(null)) {
            if (current instanceof LambdaExpr
                    || current instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true, если узел стоит в блоке try, у которого есть catch без повторного броска, и этот try лежит
     * внутри boundary: отказ здесь уже ждут и дальше он не идет
     */
    private boolean isHandledWithin(Node node, Node boundary) {
        Node current = node;
        while (current != null && current != boundary) {
            Node parent = current.getParentNode().orElse(null);
            if (parent instanceof TryStmt statement && statement.getTryBlock() == current
                    && statement.getCatchClauses().stream().anyMatch(clause -> !Messaging.rethrows(clause))) {
                return true;
            }
            current = parent;
        }
        return false;
    }

    // Метод сам гасит отказы: @Async выполняется в другом потоке, запасной метод принимает исключение на себя,
    // а catch (Exception) без повторного броска - признак того, что ошибки здесь обрабатывают на месте
    private boolean absorbsFailure(MethodDeclaration method) {
        if (Annotations.has(method, ASYNC) || method.getAnnotations().stream()
                .anyMatch(annotation -> FALLBACK_MEMBERS.stream().anyMatch(annotation.toString()::contains))) {
            return true;
        }
        boolean recovers = method.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(owner -> owner.getMethods().stream().anyMatch(other -> Annotations.has(other, RECOVER)))
                .isPresent();
        return recovers || method.findAll(TryStmt.class).stream()
                .flatMap(statement -> statement.getCatchClauses().stream())
                .anyMatch(clause -> Messaging.catchesEverything(clause) && !Messaging.rethrows(clause));
    }

    private Optional<String> describeEscapingRemote(Node node) {
        return describeRemote(node).filter(operation -> !isHandledWithin(node, null));
    }

    // HTTP-запрос известным клиентом, отправка в очередь (кроме Kafka: там send() ответа не ждет), отправка письма
    private Optional<String> describeRemote(Node node) {
        if (!(node instanceof MethodCallExpr call)) {
            return Optional.empty();
        }
        Optional<String> http = HttpCalls.describeKnownClient(call)
                .filter(operation -> !URL_OPENING.matcher(operation).find());
        if (http.isPresent()) {
            return http;
        }
        if (!Messaging.isKafkaSend(call) && Messaging.describeSend(call).isPresent()) {
            return Messaging.describeSend(call);
        }
        boolean mail = SEND.equals(call.getNameAsString()) && call.getScope()
                .filter(scope -> LocalTypes.isAnyOf(scope, MAIL_SENDERS,
                        () -> MAIL_SENDER_NAME.matcher(MethodCalls.receiverName(scope)).matches()))
                .isPresent();
        return mail ? Optional.of(describe(call)) : Optional.empty();
    }

    private String describe(MethodCallExpr call) {
        return call.getScope().map(scope -> MethodCalls.receiverName(scope) + ".").orElse("") + call.getNameAsString();
    }
}
