package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Map;

@Component
public class StacktraceExposureRule extends AbstractConfigRule {
    // Свойство и значение, при котором подробности ошибки попадают в ответ
    private static final Map<String, String> EXPOSING_VALUES = Map.of(
            "server.error.include-stacktrace", "always",
            "server.error.include-exception", "true",
            "server.error.include-message", "always",
            "server.error.include-binding-errors", "always");

    @Override
    public String code() {
        return RuleCodes.STACKTRACE_EXPOSURE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет выдачу стека и текста ошибок в HTTP-ответе";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        if (configFile.isNonProduction()) {
            return List.of();
        }
        return EXPOSING_VALUES.entrySet().stream()
                .flatMap(exposing -> configFile.find(exposing.getKey())
                        .filter(property -> exposing.getValue().equalsIgnoreCase(property.value()))
                        .stream())
                .sorted((left, right) -> Integer.compare(left.line(), right.line()))
                .map(property -> violation(configFile, property.line(),
                        property.key() + "=" + property.value() + ": подробности ошибки уходят клиенту в ответе -"
                                + " стек и текст исключения раскрывают имена классов, SQL и версии библиотек;"
                                + " задайте never, а подробности пишите в лог"))
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
