package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.SpringVersions;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ThreadPoolTaskExecutor} и {@code ThreadPoolTaskScheduler} при закрытии контекста останавливаются
 * вызовом {@code shutdownNow()}: выполняющиеся задачи прерываются, а очередь очищается - если не задан
 * {@code setWaitForTasksToCompleteOnShutdown(true)}. При каждой выкладке письма, уведомления и платежи, которые
 * стояли в очереди пула, пропадают без следа, а начатые обрываются на середине.
 * <p>
 * Со Spring Framework 6.1 (Spring Boot 3.2) пул при закрытии контекста сначала перестает принимать задачи и
 * дает уже принятым время на завершение, поэтому правило относится только к более ранним версиям.
 */
@Component
public class ExecutorTasksLostOnShutdownRule extends AbstractContextRule {
    private static final Set<String> POOLS = Set.of("ThreadPoolTaskExecutor", "ThreadPoolTaskScheduler");
    private static final String WAIT_FOR_TASKS = "setWaitForTasksToCompleteOnShutdown";
    // С этой версии Spring Boot идет Spring Framework 6.1 с плавной остановкой пулов
    private static final int GRACEFUL_MAJOR = 3;
    private static final int GRACEFUL_MINOR = 2;
    // Пакеты до переезда на jakarta: признак Spring Boot 2 и Spring Framework 5
    private static final List<String> LEGACY_IMPORTS =
            List.of("javax.persistence.", "javax.servlet.", "javax.annotation.PostConstruct", "javax.validation.");

    @Override
    public String code() {
        return RuleCodes.EXECUTOR_TASKS_LOST_ON_SHUTDOWN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ThreadPoolTaskExecutor и ThreadPoolTaskScheduler без"
                + " setWaitForTasksToCompleteOnShutdown(true): при остановке приложения задачи из очереди пропадают,"
                + " а начатые прерываются (Spring Framework до 6.1)";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        if (!stopsAbruptly(context)) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            for (VariableDeclarator variable : sourceFile.findAll(VariableDeclarator.class)) {
                Optional<ObjectCreationExpr> creation = variable.getInitializer()
                        .map(Nodes::unwrap)
                        .filter(Expression::isObjectCreationExpr)
                        .map(Expression::asObjectCreationExpr);
                // ThreadPoolTaskExecutor executor = new MonitoredExecutor(): пул узнаем и по типу переменной
                boolean isPool = creation.isPresent()
                        && (POOLS.contains(LocalTypes.typeName(variable.getType()))
                        || POOLS.contains(creation.get().getType().getNameAsString()));
                if (!isPool || TestClasses.isInside(variable) || waitsForTasks(variable)) {
                    continue;
                }
                String type = POOLS.contains(LocalTypes.typeName(variable.getType()))
                        ? LocalTypes.typeName(variable.getType())
                        : creation.get().getType().getNameAsString();
                violations.add(violation(sourceFile, variable,
                        "Пул '" + variable.getNameAsString() + "' (" + type + ") создан без"
                                + " setWaitForTasksToCompleteOnShutdown(true): при остановке приложения Spring"
                                + " вызывает у него shutdownNow() - выполняющиеся задачи прерываются, а те, что"
                                + " ждали в очереди, отбрасываются. При каждой выкладке принятая, но не"
                                + " выполненная работа пропадает; задайте"
                                + " setWaitForTasksToCompleteOnShutdown(true) и setAwaitTerminationSeconds(...) с"
                                + " запасом на самую долгую задачу"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    // Версия Spring Boot из файлов сборки; если ее там нет (свой родительский pom) - по пакетам javax в коде
    private boolean stopsAbruptly(ScanContext context) {
        List<SpringVersions.Version> versions = SpringVersions.boot(context.folderFiles());
        if (!versions.isEmpty()) {
            return versions.stream().noneMatch(version -> version.isAtLeast(GRACEFUL_MAJOR, GRACEFUL_MINOR, 0));
        }
        return context.sources().stream()
                .flatMap(source -> source.unit().getImports().stream())
                .map(declaration -> declaration.getNameAsString())
                .anyMatch(name -> LEGACY_IMPORTS.stream().anyMatch(name::startsWith));
    }

    // executor.setWaitForTasksToCompleteOnShutdown(true) в том же методе; значение из настроек тоже принимаем
    private boolean waitsForTasks(VariableDeclarator variable) {
        String name = variable.getNameAsString();
        Optional<Node> scope = Nodes.enclosingCallable(variable)
                .or(() -> variable.findAncestor(TypeDeclaration.class).map(type -> (Node) type));
        return scope.stream()
                .flatMap(callable -> callable.findAll(MethodCallExpr.class).stream())
                .filter(call -> WAIT_FOR_TASKS.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getScope()
                        .filter(owner -> owner.toString().replace("this.", "").equals(name))
                        .isPresent())
                .anyMatch(call -> !Nodes.unwrap(call.getArgument(0)).toString().equals("false"));
    }
}
