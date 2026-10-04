package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
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
public class CheckLongMethodRule extends AbstractRule {
    private static final RuleParameter MAX_LINES =
            new RuleParameter("max-lines", 50, "Допустимое число строк в методе");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_LINES);
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_LONG_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы длиннее " + value(MAX_LINES) + " строк";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            // В тестах длинные методы с подготовкой данных - обычное дело
            if (method.getBody().isEmpty() || TestClasses.isInside(method)) {
                continue;
            }

            int lines = method.getBody().get().getRange()
                    .map(range -> range.end.line - range.begin.line + 1)
                    .orElse(0);
            if (lines > value(MAX_LINES)) {
                violations.add(violation(sourceFile, method,
                        "Метод '" + method.getNameAsString() + "' занимает " + lines + " строк при допустимых "
                                + value(MAX_LINES) + ": такой метод делает несколько дел сразу, его трудно читать"
                                + " и тестировать; разбейте на методы поменьше"));
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
