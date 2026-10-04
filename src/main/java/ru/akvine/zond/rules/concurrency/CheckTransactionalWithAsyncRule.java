package ru.akvine.zond.rules.concurrency;

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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckTransactionalWithAsyncRule extends AbstractRule {
    private static final String ASYNC = "Async";
    private static final String START = "start";
    private static final String EXECUTE = "execute";
    private static final String THREAD = "Thread";

    // Запускают работу в другом потоке
    private static final Set<String> ASYNC_STARTS = Set.of("submit", "schedule", "runAsync", "supplyAsync");

    @Override
    public String code() {
        return RuleCodes.CHECK_TRANSACTIONAL_WITH_ASYNC_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет асинхронный запуск внутри транзакции и сочетание @Transactional с @Async";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
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
        return ErrorType.CONCURRENCY;
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
