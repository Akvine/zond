package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class CheckSecretInToStringRule extends AbstractRule {
    private static final String TO_STRING = "toString";
    private static final String TO_STRING_EXCLUDE = "ToString.Exclude";

    // Lombok включает в toString все поля класса
    private static final Set<String> TO_STRING_GENERATORS = Set.of("Data", "ToString", "Value");

    @Override
    public String code() {
        return RuleCodes.CHECK_SECRET_IN_TO_STRING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пароли и токены, которые попадают в toString()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            boolean generated = Annotations.hasAny(type, TO_STRING_GENERATORS);
            Set<String> printed = findFieldsUsedInToString(type);

            for (FieldDeclaration field : type.getFields()) {
                boolean excluded = field.getAnnotations().stream()
                        .anyMatch(annotation -> annotation.getNameAsString().equals(TO_STRING_EXCLUDE));
                for (VariableDeclarator variable : field.getVariables()) {
                    String name = variable.getNameAsString();
                    boolean isPrinted = printed.contains(name) || (generated && !excluded && !field.isStatic());
                    if (isPrinted && Secrets.isSecretName(name)) {
                        violations.add(violation(sourceFile, variable,
                                "Секретное поле '" + name + "' попадает в toString() класса '"
                                        + type.getNameAsString() + "': первая же запись объекта в лог или в текст"
                                        + " исключения раскроет его; исключите поле: @ToString.Exclude"));
                    }
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

    // Поля, на которые ссылается написанный вручную toString()
    private Set<String> findFieldsUsedInToString(ClassOrInterfaceDeclaration type) {
        Set<String> used = new HashSet<>();
        for (MethodDeclaration method : type.getMethodsByName(TO_STRING)) {
            method.findAll(NameExpr.class).forEach(name -> used.add(name.getNameAsString()));
            method.findAll(FieldAccessExpr.class).forEach(access -> used.add(access.getNameAsString()));
        }
        return used;
    }
}
