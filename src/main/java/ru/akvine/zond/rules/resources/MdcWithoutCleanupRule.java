package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MDC привязан к потоку, а потоки в пуле переиспользуются: значение, которое не убрали, попадет в логи
 * следующего запроса или задачи на том же потоке.
 */
@Component
public class MdcWithoutCleanupRule extends AbstractRule {
    // MDC - slf4j и log4j 1.x, ThreadContext - log4j 2
    private static final Set<String> CONTEXTS = Set.of("MDC", "ThreadContext");
    private static final String PUT = "put";
    // setContextMap возвращает потоку контекст, который был до нас, - это тоже очистка
    private static final Set<String> CLEANUP = Set.of("remove", "clear", "clearAll", "clearMap", "removeAll", "setContextMap");

    @Override
    public String code() {
        return RuleCodes.MDC_WITHOUT_CLEANUP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись в MDC без очистки в finally";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            List<MethodCallExpr> puts = calls(method, Set.of(PUT));
            // Контекст, заданный при запуске, остается на все время работы приложения намеренно
            if (puts.isEmpty() || CodeContexts.isStartup(puts.get(0))) {
                continue;
            }
            List<MethodCallExpr> cleanups = calls(method, CLEANUP);
            if (cleanups.isEmpty()) {
                // Фильтр и перехватчик кладут значение в одном методе, а убирают в другом - в том, который
                // сам ничего не кладет
                boolean cleanedElsewhere = method.findAncestor(ClassOrInterfaceDeclaration.class)
                        .filter(type -> type.getMethods().stream().anyMatch(other ->
                                calls(other, Set.of(PUT)).isEmpty() && !calls(other, CLEANUP).isEmpty()))
                        .isPresent();
                if (!cleanedElsewhere) {
                    violations.add(violation(sourceFile, puts.get(0),
                            "В MDC кладется значение, а убирать его некому: поток вернется в пул вместе с ним,"
                                    + " и чужой идентификатор попадет в логи следующего запроса или задачи;"
                                    + " уберите значение в finally (MDC.remove / MDC.clear) либо используйте"
                                    + " MDC.putCloseable в try-with-resources"));
                }
            } else if (cleanups.stream().noneMatch(this::isInFinally)) {
                violations.add(violation(sourceFile, cleanups.get(0),
                        "MDC очищается не в finally: при исключении до этой строки значение останется на потоке"
                                + " и попадет в логи следующего запроса или задачи; перенесите очистку в finally"));
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

    private List<MethodCallExpr> calls(MethodDeclaration method, Set<String> names) {
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> names.contains(call.getNameAsString()) && isOnContext(call))
                .toList();
    }

    private boolean isOnContext(MethodCallExpr call) {
        return call.getScope().filter(scope -> CONTEXTS.contains(scope.toString())).isPresent();
    }

    private boolean isInFinally(MethodCallExpr call) {
        return call.findAncestor(TryStmt.class, statement ->
                statement.getFinallyBlock().filter(block -> block.isAncestorOf(call)).isPresent()).isPresent();
    }
}
