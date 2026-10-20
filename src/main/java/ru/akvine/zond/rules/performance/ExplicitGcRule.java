package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * System.gc() и Runtime.getRuntime().gc(): полная сборка мусора с остановкой всех потоков приложения
 */
@Component
public class ExplicitGcRule extends AbstractRule {
    private static final String GC = "gc";
    private static final Set<String> OWNERS = Set.of("System", "Runtime.getRuntime()");
    // При остановке приложения пауза никому не мешает: contextDestroyed, @PreDestroy, close, shutdown
    private static final Pattern SHUTDOWN_METHOD = Pattern.compile(
            "(?i)(contextDestroyed|destroy|close|shutdown|stop|dispose|terminate|onShutdown).*");
    private static final String PRE_DESTROY = "PreDestroy";

    @Override
    public String code() {
        return RuleCodes.EXPLICIT_GC_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет явный вызов сборщика мусора: System.gc(), Runtime.getRuntime().gc()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> GC.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(call -> call.getScope().filter(scope -> OWNERS.contains(scope.toString())).isPresent())
                .filter(call -> !TestClasses.isInside(call) && !isOnShutdown(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' запускает полную сборку мусора: все потоки приложения стоят, пока она идет,"
                                + " а сборщик и без подсказки знает, когда собирать; уберите вызов - память"
                                + " освобождается сама, когда на объекты не остается ссылок"))
                .toList();
    }

    private boolean isOnShutdown(MethodCallExpr call) {
        return call.findAncestor(MethodDeclaration.class)
                .filter(method -> SHUTDOWN_METHOD.matcher(method.getNameAsString()).matches()
                        || Annotations.has(method, PRE_DESTROY))
                .isPresent();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
