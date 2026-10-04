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
import ru.akvine.zond.rules.support.Annotations;

import java.util.List;

@Component
public class CheckTooManyParametersRule extends AbstractRule {
    private static final RuleParameter MAX_PARAMETERS =
            new RuleParameter("max-parameters", 4, "Допустимое число параметров метода");
    private static final String OVERRIDE = "Override";

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_PARAMETERS);
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_TOO_MANY_PARAMETERS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы, у которых больше " + value(MAX_PARAMETERS) + " параметров";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // У переопределенного метода сигнатуру диктует интерфейс или родительский класс
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getParameters().size() > value(MAX_PARAMETERS))
                .filter(method -> !Annotations.has(method, OVERRIDE))
                .map(method -> violation(sourceFile, method,
                        "У метода '" + method.getNameAsString() + "' " + method.getParameters().size()
                                + " параметров при допустимых " + value(MAX_PARAMETERS) + ": такие вызовы трудно читать"
                                + " и легко перепутать аргументы местами; объедините параметры в объект"))
                .toList();
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
