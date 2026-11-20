package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.loaders.ProjectDeclarations;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ExceptionMessageToClientRule extends AbstractRule {
    private static final String EXCEPTION_HANDLER = "ExceptionHandler";

    // Текст бизнес-исключения обычно предназначен клиенту. У общих исключений он раскрывает устройство приложения
    private static final Set<String> BROAD_EXCEPTIONS = Set.of("Exception", "Throwable", "RuntimeException");
    private static final Set<String> DETAIL_METHODS =
            Set.of("getMessage", "getLocalizedMessage", "getStackTrace", "getCause", "toString");

    @Override
    public String code() {
        return RuleCodes.EXCEPTION_MESSAGE_TO_CLIENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обработчики общих исключений, которые отдают клиенту текст или стек исключения";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration handler : sourceFile.findAll(MethodDeclaration.class)) {
            Optional<Parameter> exception = handler.getParameters().stream()
                    .filter(parameter -> BROAD_EXCEPTIONS.contains(LocalTypes.typeName(parameter.getType())))
                    .findFirst();
            if (!Annotations.has(handler, EXCEPTION_HANDLER) || exception.isEmpty()
                    || handlesOnlyOwnExceptions(handler)) {
                continue;
            }

            String name = exception.get().getNameAsString();
            for (MethodCallExpr call : handler.findAll(MethodCallExpr.class)) {
                boolean readsDetails = DETAIL_METHODS.contains(call.getNameAsString())
                        && call.getScope().filter(scope -> scope.toString().equals(name)).isPresent();
                if (readsDetails && !isInsideLogCall(call) && !isNarrowedByType(call, name)) {
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

    // @ExceptionHandler({OrderException.class, PaymentException.class}) R handle(Exception e): тип параметра общий,
    // но обработчик получает только перечисленные исключения. Если все они объявлены в проекте, их текст
    // приложение пишет для клиента само. Текст SQLException или IOException по-прежнему раскрывает внутреннее
    private boolean handlesOnlyOwnExceptions(MethodDeclaration handler) {
        List<ClassExpr> handled = Annotations.find(handler, EXCEPTION_HANDLER).stream()
                .flatMap(annotation -> annotation.findAll(ClassExpr.class).stream())
                .toList();
        return !handled.isEmpty() && handled.stream().allMatch(type -> isOwnException(type.getType(), handler));
    }

    private boolean isOwnException(Type type, Node usage) {
        return !BROAD_EXCEPTIONS.contains(LocalTypes.typeName(type))
                && ProjectDeclarations.of(usage).find(LocalTypes.typeName(type), usage).isPresent();
    }

    // if (e instanceof OrderException) { result.setMessage(e.getMessage()); }: в этой ветке исключение свое
    private boolean isNarrowedByType(MethodCallExpr call, String exception) {
        Node child = call;
        for (Node current = call.getParentNode().orElse(null);
             current != null && !(current instanceof MethodDeclaration);
             current = current.getParentNode().orElse(null)) {
            boolean narrowed = current instanceof IfStmt branch && branch.getThenStmt() == child
                    && branch.getCondition().findAll(InstanceOfExpr.class).stream()
                    .anyMatch(check -> check.getExpression().toString().equals(exception)
                            && isOwnException(check.getType(), call));
            if (narrowed) {
                return true;
            }
            child = current;
        }
        return false;
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
