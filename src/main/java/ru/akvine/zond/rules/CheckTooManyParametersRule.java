package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckTooManyParametersRule extends AbstractRule {
    private static final int MAX_PARAMETERS = 4;
    private static final String OVERRIDE = "Override";

    @Override
    public String code() {
        return RuleCodes.CHECK_TOO_MANY_PARAMETERS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы, у которых больше " + MAX_PARAMETERS + " параметров";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // У переопределенного метода сигнатуру диктует интерфейс или родительский класс
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> method.getParameters().size() > MAX_PARAMETERS)
                .filter(method -> !Annotations.has(method, OVERRIDE))
                .map(method -> violation(sourceFile, method,
                        "У метода '" + method.getNameAsString() + "' " + method.getParameters().size()
                                + " параметров при допустимых " + MAX_PARAMETERS + ": такие вызовы трудно читать"
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
