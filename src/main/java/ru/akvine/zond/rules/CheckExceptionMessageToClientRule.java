package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckExceptionMessageToClientRule extends AbstractRule {
    private static final String EXCEPTION_HANDLER = "ExceptionHandler";

    // Текст бизнес-исключения обычно предназначен клиенту. У общих исключений он раскрывает устройство приложения
    private static final Set<String> BROAD_EXCEPTIONS = Set.of("Exception", "Throwable", "RuntimeException");
    private static final Set<String> DETAIL_METHODS =
            Set.of("getMessage", "getLocalizedMessage", "getStackTrace", "getCause", "toString");

    @Override
    public String code() {
        return RuleCodes.CHECK_EXCEPTION_MESSAGE_TO_CLIENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обработчики общих исключений, которые отдают клиенту текст или стек исключения";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration handler : sourceFile.unit().findAll(MethodDeclaration.class)) {
            Optional<Parameter> exception = handler.getParameters().stream()
                    .filter(parameter -> BROAD_EXCEPTIONS.contains(LocalTypes.typeName(parameter.getType())))
                    .findFirst();
            if (!Annotations.has(handler, EXCEPTION_HANDLER) || exception.isEmpty()) {
                continue;
            }

            String name = exception.get().getNameAsString();
            for (MethodCallExpr call : handler.findAll(MethodCallExpr.class)) {
                boolean readsDetails = DETAIL_METHODS.contains(call.getNameAsString())
                        && call.getScope().filter(scope -> scope.toString().equals(name)).isPresent();
                if (readsDetails && !isInsideLogCall(call)) {
                    violations.add(violation(sourceFile, call,
                            "'" + call + "' в обработчике общего исключения уходит в ответ клиенту: текст"
                                    + " непредвиденной ошибки раскрывает SQL, пути и имена классов; запишите"
                                    + " исключение в лог, а клиенту верните общий текст и идентификатор ошибки"));
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
        return ErrorType.SECURITY;
    }

    private boolean isInsideLogCall(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof MethodDeclaration)) {
            if (current instanceof MethodCallExpr call && Loggers.isLogCall(call)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
