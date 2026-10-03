package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckAsyncReturnTypeRule extends AbstractRule {
    private static final String ASYNC = "Async";

    // Только через эти типы результат асинхронного метода доходит до вызывающего кода
    private static final Set<String> FUTURE_TYPES = Set.of(
            "Future", "CompletableFuture", "ListenableFuture", "CompletionStage", "Mono", "Flux");

    @Override
    public String code() {
        return RuleCodes.CHECK_ASYNC_RETURN_TYPE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Async-методы, которые возвращают значение не через Future";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // void ловит отдельное правило
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.has(method, ASYNC))
                .filter(method -> !method.getType().isVoidType())
                .filter(method -> !FUTURE_TYPES.contains(LocalTypes.typeName(method.getType())))
                .map(method -> violation(sourceFile, method,
                        "@Async-метод '" + method.getNameAsString() + "' возвращает " + method.getType()
                                + ": вызывающий код получит null сразу, не дожидаясь выполнения; возвращайте"
                                + " CompletableFuture<" + method.getType() + ">"))
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
}
