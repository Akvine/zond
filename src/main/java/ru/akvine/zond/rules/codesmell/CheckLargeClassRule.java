package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckLargeClassRule extends AbstractRule {
    private static final RuleParameter MAX_LINES =
            new RuleParameter("max-lines", 500, "Допустимое число строк в классе");
    private static final RuleParameter MAX_METHODS =
            new RuleParameter("max-methods", 30, "Допустимое число методов в классе");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_LINES, MAX_METHODS);
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_LARGE_CLASS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет классы длиннее " + value(MAX_LINES)
                + " строк или с числом методов больше " + value(MAX_METHODS);
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
            if (lines > value(MAX_LINES) || methods > value(MAX_METHODS)) {
                violations.add(violation(sourceFile, type,
                        "Класс '" + type.getNameAsString() + "' слишком большой (" + lines + " строк, " + methods
                                + " методов при допустимых " + value(MAX_LINES) + " и "
                                + value(MAX_METHODS) + "): у него"
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
