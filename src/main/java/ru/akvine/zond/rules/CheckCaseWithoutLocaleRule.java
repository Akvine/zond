package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckCaseWithoutLocaleRule extends AbstractRule {
    private static final Set<String> CASE_METHODS = Set.of("toLowerCase", "toUpperCase");

    @Override
    public String code() {
        return RuleCodes.CHECK_CASE_WITHOUT_LOCALE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет toLowerCase() и toUpperCase() без указания локали";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> CASE_METHODS.contains(call.getNameAsString()))
                .filter(call -> call.getArguments().isEmpty() && call.getScope().isPresent())
                .filter(call -> !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' без локали: результат зависит от языка системы - в турецкой локали"
                                + " \"TITLE\".toLowerCase() дает \"tıtle\", и сравнение ключей, имен и кодов"
                                + " перестает работать; укажите локаль: " + call.getNameAsString() + "(Locale.ROOT)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
