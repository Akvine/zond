package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
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
import ru.akvine.zond.rules.support.CallChains;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.Repositories;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class RepositoryCallInLoopRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь от цикла искать обращение к репозиторию; 0 - не искать");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public String code() {
        return RuleCodes.REPOSITORY_CALL_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обращения к репозиторию в цикле и в поэлементных операциях стримов,"
                + " в том числе спрятанные в методах, которые вызываются из цикла";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            Set<Integer> reportedLines = new HashSet<>();
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                // В цикле повторных попыток запрос повторяется по числу попыток, а не по числу элементов
                if (!CodeContexts.isRepeatedOverData(call)) {
                    continue;
                }
                describe(call, graph)
                        .filter(problem -> reportedLines.add(call.getBegin().map(position -> position.line).orElse(0)))
                        .ifPresent(problem -> violations.add(violation(sourceFile, call, problem.text()
                                + ": на каждый элемент уходит отдельный запрос к БД (проблема N+1);"
                                + " загрузите или сохраните данные одним запросом: findAllById, saveAll,"
                                + " запрос с IN").withConfidence(problem.confidence())));
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
     * Найденное обращение к репозиторию
     *
     * @param confidence обращение стоит прямо в цикле - это видно в коде; путь через вызовы установлен точно,
     *                   только если каждый вызов в нем найден по типам, а не по именам
     */
    private record Problem(String text, Confidence confidence) {
    }

    private Optional<Problem> describe(MethodCallExpr call, CallGraph graph) {
        if (Repositories.isRepositoryCall(call)) {
            return Optional.of(new Problem(
                    "Обращение к репозиторию '" + describeCall(call) + "' в цикле", Confidence.CONFIRMED));
        }

        // enrich(order) в цикле, а запрос к БД - внутри enrich или еще глубже
        for (MethodDeclaration target : graph.targetsOf(call)) {
            Optional<CallChains.Found> found = CallChains.find(
                    graph, target, value(MAX_CALL_DEPTH), method -> false, this::describeRepositoryCall);
            if (found.isPresent()) {
                return Optional.of(new Problem(
                        "Вызов '" + call.getNameAsString() + "(...)' в цикле обращается к репозиторию '"
                                + found.get().operation() + "' (через вызов " + found.get().chain() + ")",
                        found.get().confidence(graph.isExact(call))));
            }
        }
        return Optional.empty();
    }

    private Optional<String> describeRepositoryCall(Node node) {
        return node instanceof MethodCallExpr call && Repositories.isRepositoryCall(call)
                ? Optional.of(describeCall(call))
                : Optional.empty();
    }

    private String describeCall(MethodCallExpr call) {
        return call.getScope().get() + "." + call.getNameAsString();
    }
}
