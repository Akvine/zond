package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Бизнес-исключение с кодом 400. 400 Bad Request говорит клиенту, что запрос составлен неверно: не разобран,
 * не прошел проверку полей. Отказ по правилам предметной области (недостаточно средств, заказ уже оплачен) -
 * другой случай: запрос корректен, но выполнить его нельзя; для него есть 422 Unprocessable Entity.
 */
@Component
public class BusinessErrorAsBadRequestRule extends AbstractRule implements ProjectRule {
    private static final String EXCEPTION_HANDLER = "ExceptionHandler";
    private static final String RESPONSE_STATUS = "ResponseStatus";
    private static final String EXCEPTION_SUFFIX = "Exception";
    private static final String BAD_REQUEST = "BAD_REQUEST";
    private static final String BAD_REQUEST_METHOD = "badRequest";
    private static final String BAD_REQUEST_CODE = "400";
    // ResponseEntity.status(400), HttpStatusCode.valueOf(400), ProblemDetail.forStatus(400)
    private static final Set<String> STATUS_METHODS = Set.of("status", "valueOf", "forStatus", "setStatus", "sendError");
    // По имени видно, что это не отказ предметной области: неверные данные запроса (для них 400 и нужен),
    // "не найдено" (404), доступ (401, 403), конфликт (409)
    private static final Pattern OTHER_STATUS = Pattern.compile(
            "(?i).*(Validation|Invalid|BadRequest|Malformed|Parse|Format|Constraint|Bind|Missing|Required"
                    + "|IllegalArgument|NotReadable|TypeMismatch|Unsupported|NotFound|NoSuch|Unauthorized|Forbidden"
                    + "|AccessDenied|Authentication|Authorization|Conflict|Duplicate|AlreadyExists).*");

    @Override
    public String code() {
        return RuleCodes.BUSINESS_ERROR_AS_BAD_REQUEST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет бизнес-исключения, которые отдаются клиенту с кодом 400 вместо 422";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration handler : sourceFile.unit().findAll(MethodDeclaration.class)) {
                List<String> handled = Annotations.find(handler, EXCEPTION_HANDLER)
                        .map(annotation -> handledBy(annotation, handler))
                        .orElse(List.of());
                // Обработчик, который отвечает сразу и за бизнес-отказ, и за неверные данные, судить нельзя
                if (handled.isEmpty() || !handled.stream().allMatch(name -> isBusiness(name, classes))) {
                    continue;
                }
                badRequestIn(handler).ifPresent(status -> violations.add(violation(sourceFile, status, message(handled.get(0)))));
            }
            // @ResponseStatus(HttpStatus.BAD_REQUEST) на самом исключении
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                Annotations.find(type, RESPONSE_STATUS)
                        .filter(annotation -> annotation.toString().contains(BAD_REQUEST)
                                && isBusiness(type.getNameAsString(), classes))
                        .ifPresent(annotation -> violations.add(violation(sourceFile, annotation, message(type.getNameAsString()))));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Бизнес-исключение узнаем по имени: это суждение, а не факт
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    private String message(String exception) {
        return "Бизнес-исключение '" + exception + "' отдается клиенту с кодом 400: 400 Bad Request означает, что"
                + " запрос составлен неверно - не разобран или не прошел проверку полей, - а здесь запрос корректен,"
                + " но выполнить его не дают правила предметной области; для таких отказов возвращайте"
                + " 422 Unprocessable Entity (HttpStatus.UNPROCESSABLE_ENTITY), а 400 оставьте ошибкам валидации";
    }

    // @ExceptionHandler(PaymentException.class) либо @ExceptionHandler на методе с таким параметром
    private List<String> handledBy(AnnotationExpr annotation, MethodDeclaration handler) {
        List<String> listed = annotation.findAll(ClassExpr.class).stream()
                .map(type -> LocalTypes.typeName(type.getType()))
                .toList();
        if (!listed.isEmpty()) {
            return listed;
        }
        return handler.getParameters().stream()
                .map(parameter -> LocalTypes.typeName(parameter.getType()))
                .filter(name -> name.endsWith(EXCEPTION_SUFFIX))
                .toList();
    }

    // Свое исключение проекта, имя которого не говорит о другом коде ответа
    private boolean isBusiness(String name, ProjectClasses classes) {
        return name.endsWith(EXCEPTION_SUFFIX) && classes.isDeclared(name) && !OTHER_STATUS.matcher(name).matches();
    }

    /**
     * @return место, где обработчик задает код 400: @ResponseStatus, ResponseEntity.badRequest(),
     * HttpStatus.BAD_REQUEST, status(400)
     */
    private Optional<Node> badRequestIn(MethodDeclaration handler) {
        Optional<Node> annotated = Annotations.find(handler, RESPONSE_STATUS)
                .filter(annotation -> annotation.toString().contains(BAD_REQUEST))
                .map(annotation -> annotation);
        if (annotated.isPresent() || handler.getBody().isEmpty()) {
            return annotated;
        }
        for (Node node : handler.getBody().get().findAll(Node.class)) {
            if (node instanceof MethodCallExpr call && (BAD_REQUEST_METHOD.equals(call.getNameAsString()) || isStatus400(call))
                    || node instanceof FieldAccessExpr field && BAD_REQUEST.equals(field.getNameAsString())
                    || node instanceof NameExpr name && BAD_REQUEST.equals(name.getNameAsString())) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    private boolean isStatus400(MethodCallExpr call) {
        if (!STATUS_METHODS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
            return false;
        }
        Expression first = Nodes.unwrap(call.getArgument(0));
        return first.isIntegerLiteralExpr() && BAD_REQUEST_CODE.equals(first.asIntegerLiteralExpr().getValue());
    }
}
