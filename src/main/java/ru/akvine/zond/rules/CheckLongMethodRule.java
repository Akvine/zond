package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckLongMethodRule extends AbstractRule {
    private static final int MAX_LINES = 50;

    @Override
    public String code() {
        return RuleCodes.CHECK_LONG_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы длиннее " + MAX_LINES + " строк";
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
            if (lines > MAX_LINES) {
                violations.add(violation(sourceFile, method,
                        "Метод '" + method.getNameAsString() + "' занимает " + lines + " строк при допустимых "
                                + MAX_LINES + ": такой метод делает несколько дел сразу, его трудно читать"
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
