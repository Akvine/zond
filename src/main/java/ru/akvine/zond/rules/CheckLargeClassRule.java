package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckLargeClassRule extends AbstractRule {
    private static final int MAX_LINES = 500;
    private static final int MAX_METHODS = 30;

    @Override
    public String code() {
        return RuleCodes.CHECK_LARGE_CLASS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет классы длиннее " + MAX_LINES + " строк или с числом методов больше " + MAX_METHODS;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface() || TestClasses.isInside(type)) {
                continue;
            }

            int lines = type.getRange().map(range -> range.end.line - range.begin.line + 1).orElse(0);
            int methods = type.getMethods().size();
            if (lines > MAX_LINES || methods > MAX_METHODS) {
                violations.add(violation(sourceFile, type,
                        "Класс '" + type.getNameAsString() + "' слишком большой (" + lines + " строк, " + methods
                                + " методов при допустимых " + MAX_LINES + " и " + MAX_METHODS + "): у него"
                                + " несколько обязанностей; разделите его на классы по обязанностям"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }
}
