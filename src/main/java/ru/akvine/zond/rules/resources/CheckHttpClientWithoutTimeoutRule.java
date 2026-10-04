package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckHttpClientWithoutTimeoutRule extends AbstractRule {
    private static final Set<String> CLIENT_TYPES = Set.of("RestTemplate", "OkHttpClient");
    private static final String HTTP_CLIENT = "HttpClient";
    private static final String NEW_HTTP_CLIENT = "newHttpClient";

    // Признаки того, что таймауты где-то в этом файле все же задают
    private static final List<String> TIMEOUT_MARKERS = List.of("Timeout", "RestTemplateBuilder");

    @Override
    public String code() {
        return RuleCodes.CHECK_HTTP_CLIENT_WITHOUT_TIMEOUT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет HTTP-клиенты, созданные без таймаутов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        String text = sourceFile.unit().toString();
        if (TIMEOUT_MARKERS.stream().anyMatch(text::contains)) {
            return List.of();
        }

        return sourceFile.unit().findAll(Expression.class).stream()
                .flatMap(expression -> describeClient(expression)
                        .map(client -> violation(sourceFile, expression,
                                "'" + client + "' без таймаутов: по умолчанию клиент ждет ответа бесконечно,"
                                        + " зависший внешний сервис займет все потоки приложения; задайте"
                                        + " таймауты соединения и чтения"))
                        .stream())
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    // new RestTemplate(), new OkHttpClient(), HttpClient.newHttpClient()
    private Optional<String> describeClient(Expression expression) {
        if (expression.isObjectCreationExpr()) {
            String type = expression.asObjectCreationExpr().getType().getNameAsString();
            boolean bare = CLIENT_TYPES.contains(type) && expression.asObjectCreationExpr().getArguments().isEmpty();
            return bare ? Optional.of("new " + type + "()") : Optional.empty();
        }
        if (expression.isMethodCallExpr()) {
            MethodCallExpr call = expression.asMethodCallExpr();
            return MethodCalls.isCallOn(call, HTTP_CLIENT, NEW_HTTP_CLIENT)
                    ? Optional.of("HttpClient.newHttpClient()")
                    : Optional.empty();
        }
        return Optional.empty();
    }
}
