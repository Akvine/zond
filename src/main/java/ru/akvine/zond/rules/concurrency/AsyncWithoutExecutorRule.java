package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Optional;

@Component
public class AsyncWithoutExecutorRule extends AbstractRule {
    private static final String ASYNC = "Async";
    private static final String VALUE = "value";

    @Override
    public String code() {
        return RuleCodes.ASYNC_WITHOUT_EXECUTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Async без указания executor";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> ASYNC.equals(annotation.getName().getIdentifier()))
                .filter(annotation -> !hasExecutor(annotation))
                .map(annotation -> violation(sourceFile, annotation,
                        "@Async без имени executor: задачи уйдут в исполнитель по умолчанию, общий для всего"
                                + " приложения, а без настроенного пула - в SimpleAsyncTaskExecutor, который создает"
                                + " новый поток на каждый вызов; укажите пул явно: @Async(\"имяExecutor\")"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    // @Async("pool") либо @Async(value = "pool"); пустая строка означает исполнитель по умолчанию
    private boolean hasExecutor(AnnotationExpr annotation) {
        Optional<Expression> value = Optional.empty();
        if (annotation.isSingleMemberAnnotationExpr()) {
            value = Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        } else if (annotation.isNormalAnnotationExpr()) {
            value = annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> VALUE.equals(pair.getNameAsString()))
                    .map(pair -> pair.getValue())
                    .findFirst();
        }
        return value
                .filter(executor -> !executor.isStringLiteralExpr() || !executor.asStringLiteralExpr().asString().isBlank())
                .isPresent();
    }
}
