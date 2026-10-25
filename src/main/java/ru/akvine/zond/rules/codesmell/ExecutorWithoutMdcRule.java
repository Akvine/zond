package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.ExecutorCreations;

import java.util.List;
import java.util.Set;

@Component
public class ExecutorWithoutMdcRule extends AbstractRule implements ProjectRule {
    private static final String MDC = "MDC";

    // Способы передать контекст в задачи пула: декоратор Spring либо ручная обертка над задачей
    private static final Set<String> CONTEXT_PROPAGATION_METHODS = Set.of("setTaskDecorator");
    // MDC.put("traceId", ...), ThreadContext.put(...) у Log4j 2: проекту есть что передавать в потоки пула
    private static final Set<String> CONTEXT_WRITES = Set.of("put", "putCloseable", "setContextMap", "putAll");
    private static final Set<String> CONTEXT_HOLDERS = Set.of("MDC", "ThreadContext", "org.slf4j.MDC");

    @Override
    public String code() {
        return RuleCodes.EXECUTOR_WITHOUT_MDC_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пулы потоков, созданные без передачи MDC-контекста в задачи";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // Проект сам ничего не кладет в MDC (Nacos, библиотеки): передавать в потоки пула нечего
        if (sourceFiles.stream().noneMatch(this::fillsContext)) {
            return List.of();
        }
        return sourceFiles.stream().flatMap(sourceFile -> checkFile(sourceFile).stream()).toList();
    }

    private boolean fillsContext(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> CONTEXT_WRITES.contains(call.getNameAsString())
                        && call.getScope().filter(scope -> CONTEXT_HOLDERS.contains(scope.toString())).isPresent());
    }

    private List<Violation> checkFile(SourceFile sourceFile) {
        // MDC хранится в ThreadLocal, поэтому в потоках пула он пуст. Если в файле о нем нигде не вспоминают,
        // значит передачу контекста не настраивали
        if (propagatesContext(sourceFile.unit())) {
            return List.of();
        }

        return sourceFile.unit().findAll(Expression.class).stream()
                .filter(ExecutorCreations::creates)
                .map(creation -> violation(sourceFile, creation,
                        ExecutorCreations.describe(creation) + " без передачи MDC: в потоках пула контекст"
                                + " логирования (traceId, пользователь) пуст, записи задач не связать с запросом;"
                                + " оберните задачи, копируя MDC.getCopyOfContextMap(), либо задайте TaskDecorator"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean propagatesContext(CompilationUnit unit) {
        boolean usesMdc = unit.findAll(SimpleName.class).stream().anyMatch(name -> MDC.equals(name.getIdentifier()));
        return usesMdc || unit.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> CONTEXT_PROPAGATION_METHODS.contains(call.getNameAsString()));
    }
}
