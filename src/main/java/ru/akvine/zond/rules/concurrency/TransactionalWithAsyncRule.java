package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
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
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class TransactionalWithAsyncRule extends AbstractRule implements ProjectRule {
    private static final String ASYNC = "Async";
    private static final String START = "start";
    private static final String EXECUTE = "execute";
    private static final String THREAD = "Thread";

    // Запускают работу в другом потоке
    private static final Set<String> ASYNC_STARTS = Set.of("submit", "schedule", "runAsync", "supplyAsync");

    @Override
    public String code() {
        return RuleCodes.TRANSACTIONAL_WITH_ASYNC_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет асинхронный запуск внутри транзакции - в том числе вызов @Async-метода"
                + " другого бина - и сочетание @Transactional с @Async";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            check(sourceFile, graph, violations);
        }
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

    private void check(SourceFile sourceFile, CallGraph graph, List<Violation> violations) {
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            // На приватных методах @Transactional не работает в принципе, это ловит отдельное правило
            if (method.isPrivate() || TransactionalAnnotations.findEffective(method).isEmpty()) {
                continue;
            }

            if (Annotations.has(method, ASYNC)) {
                violations.add(violation(sourceFile, method,
                        "@Transactional вместе с @Async на методе '" + method.getNameAsString() + "': метод"
                                + " выполнится в другом потоке и в собственной транзакции - транзакция вызывающего"
                                + " кода на него не распространяется, ее откат этот метод не затронет"));
            }

            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (isAsyncStart(call)) {
                    violations.add(violation(sourceFile, call,
                            "Асинхронный запуск '" + call.getNameAsString() + "(...)' внутри @Transactional-метода '"
                                    + method.getNameAsString() + "': транзакция привязана к потоку и в задачу не"
                                    + " передается - задача не увидит незафиксированных изменений и не откатится"
                                    + " вместе с транзакцией; запускайте ее после фиксации"));
                    continue;
                }
                asyncTarget(call, graph).ifPresent(target -> violations.add(violation(sourceFile, call,
                        "Вызов @Async-метода '" + call.getScope().map(scope -> scope + ".").orElse("")
                                + call.getNameAsString() + "(...)' внутри @Transactional-метода '"
                                + method.getNameAsString() + "': транзакция на него не распространяется - он"
                                + " выполнится в другом потоке, не увидит незафиксированных изменений (например,"
                                + " только что сохраненную запись) и не откатится вместе с транзакцией, а его"
                                + " ошибка ее не откатит; запускайте его после фиксации:"
                                + " @TransactionalEventListener(phase = AFTER_COMMIT) либо"
                                + " TransactionSynchronization.afterCommit()")
                        .withConfidence(graph.isExact(call) ? Confidence.CONFIRMED : Confidence.PROBABLE)));
            }
        }
    }

    /**
     * @return @Async-метод другого бина, который вызывают отсюда. Вызов своего же метода идет мимо прокси и
     * выполняется в том же потоке - о нем сообщает отдельное правило
     */
    private Optional<MethodDeclaration> asyncTarget(MethodCallExpr call, CallGraph graph) {
        Optional<Expression> scope = call.getScope().map(Nodes::unwrap);
        if (scope.isEmpty() || scope.get().isThisExpr() || scope.get().isSuperExpr()) {
            return Optional.empty();
        }
        List<MethodDeclaration> targets = graph.targetsOf(call);
        // Вызов может вести в несколько реализаций: асинхронным его делает только то, что таковы они все
        boolean async = !targets.isEmpty() && targets.stream().allMatch(this::isAsync);
        return async ? Optional.of(targets.get(0)) : Optional.empty();
    }

    private boolean isAsync(MethodDeclaration method) {
        return Annotations.has(method, ASYNC) || method.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> Annotations.has(type, ASYNC))
                .isPresent() && method.isPublic();
    }

    private boolean isAsyncStart(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (ASYNC_STARTS.contains(name)) {
            return call.getScope().isPresent() && !call.getArguments().isEmpty();
        }
        // execute есть и у JdbcTemplate, и у Statement, поэтому берем только execute с лямбдой или ссылкой на метод
        if (EXECUTE.equals(name)) {
            return call.getArguments().stream()
                    .anyMatch(argument -> argument.isLambdaExpr() || argument.isMethodReferenceExpr());
        }
        return START.equals(name)
                && call.getScope().flatMap(LocalTypes::typeOf).filter(THREAD::equals).isPresent();
    }
}
