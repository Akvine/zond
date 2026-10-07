package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class FileUploadWithoutValidationRule extends AbstractRule {
    private static final Set<String> UPLOAD_TYPES = Set.of("MultipartFile", "FilePart", "Part");

    // Содержимое уходит на диск или в хранилище
    private static final Set<String> STORING = Set.of("transferTo", "getBytes", "getInputStream", "write", "getResource");
    // Обращения, по которым проверяют, что именно загрузили
    private static final Set<String> TYPE_CHECKS = Set.of("getContentType", "getOriginalFilename", "getSubmittedFileName",
            "filename", "headers", "probeContentType", "detect", "guessContentTypeFromStream", "getExtension",
            "getFilenameExtension");

    @Override
    public String code() {
        return RuleCodes.FILE_UPLOAD_WITHOUT_VALIDATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сохранение загруженного файла без проверки его типа";
    }

    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (method.getBody().isEmpty()) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                if (!UPLOAD_TYPES.contains(LocalTypes.typeName(parameter.getType()))) {
                    continue;
                }
                Optional<MethodCallExpr> stored = findCall(method, parameter, STORING);
                // Файл передан дальше целиком: проверка может стоять там, отсюда ее не видно
                if (stored.isEmpty() || isPassedOn(method, parameter) || hasTypeCheck(method)) {
                    continue;
                }
                violations.add(violation(sourceFile, stored.get(),
                        "Загруженный файл '" + parameter.getNameAsString() + "' сохраняется без проверки типа:"
                                + " клиент может прислать исполняемый файл, HTML со скриптом или архив-бомбу;"
                                + " проверяйте расширение и тип содержимого по списку допустимых и не доверяйте"
                                + " имени файла от клиента"));
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

    private Optional<MethodCallExpr> findCall(MethodDeclaration method, Parameter parameter, Set<String> names) {
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> names.contains(call.getNameAsString()))
                .filter(call -> call.getScope().filter(scope -> isParameter(scope, parameter)).isPresent())
                .findFirst();
    }

    private boolean hasTypeCheck(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream().anyMatch(call -> TYPE_CHECKS.contains(call.getNameAsString()));
    }

    private boolean isPassedOn(MethodDeclaration method, Parameter parameter) {
        return method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getArguments().stream().anyMatch(argument -> isParameter(argument, parameter)));
    }

    private boolean isParameter(Expression expression, Parameter parameter) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr() && value.asNameExpr().getNameAsString().equals(parameter.getNameAsString());
    }
}
