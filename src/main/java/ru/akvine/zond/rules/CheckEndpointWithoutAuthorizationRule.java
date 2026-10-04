package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckEndpointWithoutAuthorizationRule extends AbstractRule {
    private static final Set<String> AUTHORIZATION_ANNOTATIONS =
            Set.of("PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "PermitAll", "DenyAll");
    private static final Set<String> CHANGING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    @Override
    public String code() {
        return RuleCodes.CHECK_ENDPOINT_WITHOUT_AUTHORIZATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет изменяющие обработчики без проверки прав в контроллерах, где у других она есть";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            // Проверка на классе действует на все методы; если прав нет ни у одного метода, их задают в другом месте
            boolean usesAuthorization = type.getMethods().stream()
                    .anyMatch(method -> Annotations.hasAny(method, AUTHORIZATION_ANNOTATIONS));
            if (!usesAuthorization || Annotations.hasAny(type, AUTHORIZATION_ANNOTATIONS)) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                boolean changes = Mappings.find(method)
                        .map(Mappings::httpMethod)
                        .filter(CHANGING_METHODS::contains)
                        .isPresent();
                if (changes && !Annotations.hasAny(method, AUTHORIZATION_ANNOTATIONS)) {
                    violations.add(violation(sourceFile, method,
                            "Обработчик '" + method.getNameAsString() + "' меняет данные, но проверки прав на нем"
                                    + " нет, хотя у других методов '" + type.getNameAsString() + "' она есть:"
                                    + " похоже, ее забыли; добавьте @PreAuthorize либо явный @PermitAll"));
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
}
