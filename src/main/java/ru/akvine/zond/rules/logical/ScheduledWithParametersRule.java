package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.List;
import java.util.Set;

@Component
public class ScheduledWithParametersRule extends AbstractRule {
    private static final Set<String> SCHEDULED_ANNOTATIONS = Set.of("Scheduled", "Schedules");

    @Override
    public String code() {
        return RuleCodes.SCHEDULED_WITH_PARAMETERS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Scheduled-методы с параметрами";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.hasAny(method, SCHEDULED_ANNOTATIONS))
                .filter(method -> !method.getParameters().isEmpty())
                .map(method -> violation(sourceFile, method,
                        "@Scheduled-метод '" + method.getNameAsString() + "' принимает параметры: планировщику"
                                + " неоткуда взять аргументы, приложение упадет при старте; уберите параметры"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
