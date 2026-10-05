package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.List;

@Component
public class AsyncVoidRule extends AbstractRule {
    private static final String ASYNC = "Async";

    @Override
    public String code() {
        return RuleCodes.ASYNC_VOID_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Async-методы, которые возвращают void";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getType().isVoidType())
                .filter(this::isAsync)
                .map(method -> violation(sourceFile, method,
                        "@Async-метод '" + method.getNameAsString() + "' возвращает void: вызывающий код не узнает"
                                + " ни о завершении, ни об ошибке - исключение попадет только в"
                                + " AsyncUncaughtExceptionHandler; возвращайте CompletableFuture"))
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

    // @Async на самом методе либо на классе - тогда асинхронны все его public-методы
    private boolean isAsync(MethodDeclaration method) {
        if (Annotations.has(method, ASYNC)) {
            return true;
        }
        return method.isPublic() && method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?> type && Annotations.has(type, ASYNC))
                .isPresent();
    }
}
