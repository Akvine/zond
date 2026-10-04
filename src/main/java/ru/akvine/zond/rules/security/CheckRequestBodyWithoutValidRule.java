package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.Parameter;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.List;
import java.util.Set;

@Component
public class CheckRequestBodyWithoutValidRule extends AbstractRule {
    private static final String REQUEST_BODY = "RequestBody";
    private static final Set<String> VALIDATION_TRIGGERS = Set.of("Valid", "Validated");

    // Простые тела запроса, у которых нет полей для проверки
    private static final Set<String> NOT_VALIDATED_TYPES = Set.of("String", "JsonNode", "Map", "Object");

    @Override
    public String code() {
        return RuleCodes.CHECK_REQUEST_BODY_WITHOUT_VALID_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @RequestBody без @Valid";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(Parameter.class).stream()
                .filter(parameter -> Annotations.has(parameter, REQUEST_BODY))
                .filter(parameter -> !Annotations.hasAny(parameter, VALIDATION_TRIGGERS))
                .filter(parameter -> !NOT_VALIDATED_TYPES.contains(LocalTypes.typeName(parameter.getType()))
                        && !parameter.getType().isArrayType())
                .map(parameter -> violation(sourceFile, parameter,
                        "@RequestBody '" + parameter.getNameAsString() + "' без @Valid: аннотации валидации на"
                                + " полях DTO не сработают, в сервис попадут непроверенные данные;"
                                + " добавьте @Valid"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }
}
