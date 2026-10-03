package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class CheckGenericExceptionRule extends AbstractRule {
    private static final String OVERRIDE = "Override";
    private static final String MAIN = "main";

    private static final Set<String> GENERIC_THROWN = Set.of("Exception", "RuntimeException", "Throwable", "Error");
    private static final Set<String> GENERIC_DECLARED = Set.of("Exception", "Throwable");

    @Override
    public String code() {
        return RuleCodes.CHECK_GENERIC_EXCEPTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет слишком общие исключения: throw new RuntimeException и throws Exception";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (ThrowStmt throwStmt : sourceFile.unit().findAll(ThrowStmt.class)) {
            if (throwStmt.getExpression().isObjectCreationExpr() && !TestClasses.isInside(throwStmt)) {
                String type = throwStmt.getExpression().asObjectCreationExpr().getType().getNameAsString();
                if (GENERIC_THROWN.contains(type)) {
                    violations.add(violation(sourceFile, throwStmt,
                            "throw new " + type + "(...): вызывающий код не сможет отличить эту ошибку от любой"
                                    + " другой и обработать ее отдельно; заведите исключение с говорящим именем"));
                }
            }
        }

        // У переопределенного метода и у main сигнатуру диктует не автор; в тестах throws Exception - норма
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (Annotations.has(method, OVERRIDE) || MAIN.equals(method.getNameAsString()) || TestClasses.isInside(method)) {
                continue;
            }
            method.getThrownExceptions().stream()
                    .map(LocalTypes::typeName)
                    .filter(GENERIC_DECLARED::contains)
                    .findFirst()
                    .ifPresent(type -> violations.add(violation(sourceFile, method,
                            "Метод '" + method.getNameAsString() + "' объявляет throws " + type + ": вызывающий код"
                                    + " вынужден ловить все подряд, включая ошибки программирования; перечислите"
                                    + " конкретные исключения")));
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }
}
