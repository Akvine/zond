package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Контекст запроса в другом потоке. SecurityContextHolder и RequestContextHolder хранят данные в ThreadLocal
 * потока, который обрабатывает запрос. В @Async-методе, в задаче пула и в параллельном стриме работает другой
 * поток - там контекст пуст: пользователя нет, запроса нет.
 */
@Component
public class SecurityContextInAsyncRule extends AbstractRule implements ProjectRule {
    private static final String SECURITY_HOLDER = "SecurityContextHolder";
    private static final String REQUEST_HOLDER = "RequestContextHolder";
    private static final Set<String> HOLDER_READS = Set.of("getContext", "getRequestAttributes", "currentRequestAttributes");
    private static final String ASYNC = "Async";
    private static final String SCHEDULED = "Scheduled";
    private static final String THREAD = "Thread";
    // Лямбда, переданная этим методам, выполнится в другом потоке
    private static final Set<String> ASYNC_STARTS = Set.of(
            "submit", "execute", "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay", "invokeAll", "runAsync",
            "supplyAsync", "thenApplyAsync", "thenAcceptAsync", "thenRunAsync", "thenComposeAsync", "handleAsync",
            "whenCompleteAsync");
    private static final Set<String> PARALLEL = Set.of("parallelStream", "parallel");
    // Передача контекста настроена: обертки Spring Security, наследуемый ThreadLocal, свой TaskDecorator
    private static final List<String> PROPAGATION = List.of(
            "DelegatingSecurityContext", "MODE_INHERITABLETHREADLOCAL", "setStrategyName", "TaskDecorator",
            "setThreadContextInheritable", "ContextSnapshot");
    private static final Set<String> CONTEXT_SETTERS = Set.of("setAuthentication", "setContext", "setRequestAttributes");
    private static final int MAX_DEPTH = 3;
    private static final String ADVICE = "; прочитайте нужные данные в потоке запроса и передайте их параметром либо"
            + " настройте передачу контекста: DelegatingSecurityContextAsyncTaskExecutor, TaskDecorator";

    @Override
    public String code() {
        return RuleCodes.SECURITY_CONTEXT_IN_ASYNC_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет чтение SecurityContextHolder и RequestContextHolder в @Async-методах,"
                + " задачах пула, задачах по расписанию и параллельных стримах: в другом потоке контекст пуст";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Violation> violations = new ArrayList<>();
        if (sourceFiles.stream().anyMatch(this::configuresPropagation)) {
            return violations;
        }
        Map<CompilationUnit, SourceFile> files = new IdentityHashMap<>();
        sourceFiles.forEach(sourceFile -> files.put(sourceFile.unit(), sourceFile));
        Set<Node> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        Search search = new Search(CallGraph.of(sourceFiles), files, reported, violations);
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr read : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (!isHolderRead(read) || TestClasses.isInside(read) || isContextSetUp(read)) {
                    continue;
                }
                String holder = read.getScope().map(Object::toString).orElse("");
                Optional<String> place = asyncPlace(read);
                if (place.isPresent() && reported.add(read)) {
                    violations.add(violation(sourceFile, read, message(holder, place.get(), "")));
                    continue;
                }
                // Чтение спрятано в методе, который вызывают из другого потока: currentUser() внутри @Async
                read.findAncestor(MethodDeclaration.class).ifPresent(reader -> reportCallers(reader, holder, search));
            }
        }
        violations.sort(Comparator.comparing((Violation found) -> found.file().toString()).thenComparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    /**
     * Все, что нужно для поиска вверх по вызовам
     *
     * @param files    файл по его дереву разбора: место вызова может лежать в другом файле
     * @param reported места, о которых уже сообщили
     */
    private record Search(CallGraph graph, Map<CompilationUnit, SourceFile> files, Set<Node> reported, List<Violation> violations) {
    }

    private boolean configuresPropagation(SourceFile sourceFile) {
        String text = sourceFile.unit().toString();
        return PROPAGATION.stream().anyMatch(text::contains);
    }

    // Идем вверх по вызовам: кто из другого потока добирается до метода, читающего контекст
    private void reportCallers(MethodDeclaration reader, String holder, Search search) {
        Set<MethodDeclaration> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<MethodDeclaration> frontier = List.of(reader);
        for (int depth = 0; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<MethodDeclaration> next = new ArrayList<>();
            for (MethodDeclaration method : frontier) {
                for (CallGraph.Call call : search.graph().callsTo(method)) {
                    Optional<String> place = asyncPlace(call.site());
                    Optional<SourceFile> file = call.site().findCompilationUnit().map(search.files()::get);
                    if (place.isPresent() && file.isPresent() && search.reported().add(call.site())) {
                        search.violations().add(violation(file.get(), call.site(),
                                message(holder, place.get(), " (через вызов '" + call.site().getNameAsString() + "(...)')")));
                    } else if (place.isEmpty() && visited.add(call.caller())) {
                        next.add(call.caller());
                    }
                }
            }
            frontier = next;
        }
    }

    private String message(String holder, String place, String path) {
        boolean scheduled = place.startsWith("задаче по расписанию");
        return holder + " читается в " + place + path + ": контекст хранится в ThreadLocal потока, который"
                + " обрабатывает запрос, а этот код выполняется в другом потоке - "
                + (SECURITY_HOLDER.equals(holder)
                ? "getAuthentication() вернет null, и пользователь окажется неизвестен"
                : "атрибутов запроса там нет: getRequestAttributes() вернет null, а currentRequestAttributes()"
                + " бросит IllegalStateException")
                + (scheduled ? "; у задачи по расписанию запроса и пользователя нет вовсе - задайте их явно" : ADVICE);
    }

    // Задача сама задает контекст: SecurityContextHolder.getContext().setAuthentication(systemUser)
    private boolean isContextSetUp(MethodCallExpr read) {
        return Nodes.enclosingCallable(read).stream()
                .flatMap(callable -> callable.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> CONTEXT_SETTERS.contains(call.getNameAsString()));
    }

    private boolean isHolderRead(MethodCallExpr call) {
        return HOLDER_READS.contains(call.getNameAsString()) && call.getScope()
                .filter(scope -> SECURITY_HOLDER.equals(scope.toString()) || REQUEST_HOLDER.equals(scope.toString()))
                .isPresent();
    }

    /**
     * @return описание места, если код в нем выполняется не в потоке запроса
     */
    private Optional<String> asyncPlace(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof MethodDeclaration)) {
            if (current instanceof LambdaExpr lambda) {
                Optional<String> start = asyncStart(lambda);
                if (start.isPresent()) {
                    return start;
                }
            }
            current = current.getParentNode().orElse(null);
        }
        if (!(current instanceof MethodDeclaration method)) {
            return Optional.empty();
        }
        boolean asyncClass = method.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> Annotations.has(type, ASYNC))
                .isPresent() && method.isPublic();
        if (Annotations.has(method, ASYNC) || asyncClass) {
            return Optional.of("@Async-методе '" + method.getNameAsString() + "'");
        }
        return Annotations.has(method, SCHEDULED)
                ? Optional.of("задаче по расписанию '" + method.getNameAsString() + "'")
                : Optional.empty();
    }

    // executor.submit(() -> ...), CompletableFuture.runAsync(() -> ...), new Thread(() -> ...), parallelStream().map(x -> ...)
    private Optional<String> asyncStart(LambdaExpr lambda) {
        Node owner = lambda.getParentNode().orElse(null);
        if (owner instanceof ObjectCreationExpr creation && THREAD.equals(creation.getType().getNameAsString())) {
            return Optional.of("новом потоке (new Thread)");
        }
        if (!(owner instanceof MethodCallExpr call)) {
            return Optional.empty();
        }
        if (ASYNC_STARTS.contains(call.getNameAsString())) {
            return Optional.of("задаче, переданной в '" + call.getNameAsString() + "(...)'");
        }
        boolean parallel = StreamChains.callsBefore(call).stream()
                .anyMatch(step -> PARALLEL.contains(step.getNameAsString()) && step.getArguments().isEmpty());
        return parallel ? Optional.of("параллельном стриме") : Optional.empty();
    }
}
