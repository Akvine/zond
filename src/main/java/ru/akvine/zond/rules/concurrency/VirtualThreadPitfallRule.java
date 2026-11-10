package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.HttpCalls;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ловушки виртуальных потоков (Java 21). Виртуальный поток, который блокируется внутри synchronized, до JDK 24
 * не отпускает поток-носитель: носителей столько же, сколько ядер, и несколько таких блокировок останавливают
 * все виртуальные потоки приложения. А собирать виртуальные потоки в пул бессмысленно: они дешевые, и пул только
 * ограничивает то, ради чего их включали.
 */
@Component
public class VirtualThreadPitfallRule extends AbstractContextRule {
    private static final String VIRTUAL_ENABLED_KEY = "spring.threads.virtual.enabled";
    private static final String TRUE = "true";
    private static final List<String> VIRTUAL_MARKERS = List.of(
            "newVirtualThreadPerTaskExecutor", "ofVirtual()", "startVirtualThread", "newThreadPerTaskExecutor");
    private static final String OF_VIRTUAL = "ofVirtual()";
    private static final String EXECUTORS = "Executors";
    private static final Set<String> POOL_FACTORIES = Set.of(
            "newFixedThreadPool", "newCachedThreadPool", "newScheduledThreadPool", "newSingleThreadExecutor",
            "newWorkStealingPool");
    private static final Set<String> POOL_TYPES = Set.of("ThreadPoolExecutor", "ScheduledThreadPoolExecutor");
    // Блокирующие вызовы, которые узнаются по имени: пауза, JDBC, ожидание результата
    private static final Set<String> BLOCKING_METHODS = Set.of(
            "sleep", "executeQuery", "executeUpdate", "getConnection", "readAllBytes", "readString", "readAllLines");

    @Override
    public String code() {
        return RuleCodes.VIRTUAL_THREAD_PITFALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект с виртуальными потоками и ищет блокирующие вызовы внутри synchronized (поток"
                + " прикрепляется к носителю) и пулы из виртуальных потоков";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        boolean enabledInConfig = context.configFiles().stream()
                .map(config -> config.find(VIRTUAL_ENABLED_KEY))
                .flatMap(Optional::stream)
                .anyMatch(property -> TRUE.equalsIgnoreCase(property.value()));
        boolean usedInCode = context.sources().stream().anyMatch(this::usesVirtualThreads);
        if (!enabledInConfig && !usedInCode) {
            return violations;
        }
        for (SourceFile sourceFile : context.sources()) {
            for (Node node : sourceFile.unit().findAll(Node.class)) {
                if (TestClasses.isInside(node)) {
                    continue;
                }
                if (isVirtualPool(node)) {
                    violations.add(violation(sourceFile, node,
                            "Пул из виртуальных потоков ('" + Nodes.text(node) + "'): виртуальные потоки дешевые,"
                                    + " их создают по одному на задачу и не переиспользуют - пул только ограничивает"
                                    + " их число и ничего не экономит; используйте"
                                    + " Executors.newVirtualThreadPerTaskExecutor(), а число одновременных обращений"
                                    + " к ресурсу ограничивайте через Semaphore").withLevel(ErrorLevel.MINOR));
                }
                blockingUnderMonitor(node).ifPresent(blocking -> violations.add(violation(sourceFile, blocking,
                        "Блокирующий вызов '" + describe(blocking) + "' внутри synchronized в проекте с"
                                + " виртуальными потоками: до JDK 24 виртуальный поток, который блокируется внутри"
                                + " synchronized, не отпускает поток-носитель (pinning). Носителей столько же, сколько"
                                + " ядер, и несколько таких вызовов останавливают все виртуальные потоки приложения;"
                                + " замените synchronized на ReentrantLock либо вынесите вызов из-под блокировки")));
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

    private boolean usesVirtualThreads(SourceFile sourceFile) {
        String text = sourceFile.unit().toString();
        return VIRTUAL_MARKERS.stream().anyMatch(text::contains);
    }

    // Executors.newFixedThreadPool(100, Thread.ofVirtual().factory()), new ThreadPoolExecutor(..., virtualFactory)
    private boolean isVirtualPool(Node node) {
        List<Expression> arguments;
        if (node instanceof MethodCallExpr call && POOL_FACTORIES.contains(call.getNameAsString())
                && call.getScope().filter(scope -> EXECUTORS.equals(scope.toString())).isPresent()) {
            arguments = call.getArguments();
        } else if (node instanceof ObjectCreationExpr creation && POOL_TYPES.contains(creation.getType().getNameAsString())) {
            arguments = creation.getArguments();
        } else {
            return false;
        }
        return arguments.stream().anyMatch(argument -> argument.toString().contains(OF_VIRTUAL)
                || LocalTypes.findInitializer(argument).filter(value -> value.toString().contains(OF_VIRTUAL)).isPresent());
    }

    /**
     * @return первый блокирующий вызов в synchronized-блоке либо в synchronized-методе
     */
    private Optional<MethodCallExpr> blockingUnderMonitor(Node node) {
        boolean monitor = node instanceof SynchronizedStmt
                || node instanceof MethodDeclaration method && method.isSynchronized();
        if (!monitor) {
            return Optional.empty();
        }
        return node.findAll(MethodCallExpr.class).stream()
                .filter(call -> !Nodes.isInNestedScope(call, node))
                .filter(this::isBlocking)
                .findFirst();
    }

    private boolean isBlocking(MethodCallExpr call) {
        return BLOCKING_METHODS.contains(call.getNameAsString()) && call.getScope().isPresent()
                || HttpCalls.describeKnownClient(call).isPresent()
                || Repositories.isRepositoryCall(call);
    }

    private String describe(MethodCallExpr call) {
        return call.getScope().map(scope -> Nodes.text(scope) + ".").orElse("") + call.getNameAsString();
    }
}
