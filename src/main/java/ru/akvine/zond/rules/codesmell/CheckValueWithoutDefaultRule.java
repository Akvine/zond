package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CheckValueWithoutDefaultRule extends AbstractRule {
    private static final String VALUE = "Value";

    // ${some.key} без двоеточия, то есть без значения по умолчанию
    private static final Pattern PLACEHOLDER_WITHOUT_DEFAULT = Pattern.compile(".*\\$\\{[^:}]+}.*");

    @Override
    public String code() {
        return RuleCodes.CHECK_VALUE_WITHOUT_DEFAULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Value без значения по умолчанию";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
            if (!VALUE.equals(annotation.getName().getIdentifier())) {
                continue;
            }
            annotation.findAll(StringLiteralExpr.class).stream()
                    .map(StringLiteralExpr::asString)
                    .filter(value -> PLACEHOLDER_WITHOUT_DEFAULT.matcher(value).matches())
                    .findFirst()
                    .ifPresent(value -> violations.add(violation(sourceFile, annotation,
                            "@Value(\"" + value + "\") без значения по умолчанию: если свойства нет в окружении,"
                                    + " приложение не стартует; если свойство необязательное, задайте значение"
                                    + " по умолчанию: ${ключ:значение}")));
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
}
