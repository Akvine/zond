package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;

@Component
public class CheckActuatorExposureRule extends AbstractConfigRule {
    private static final String EXPOSURE = "management.endpoints.web.exposure.include";
    private static final String ALL = "*";

    @Override
    public String code() {
        return RuleCodes.CHECK_ACTUATOR_EXPOSURE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет Actuator, открытый наружу целиком";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        if (configFile.isNonProduction()) {
            return List.of();
        }
        return configFile.find(EXPOSURE)
                .filter(property -> property.value().contains(ALL))
                .map(property -> violation(configFile, property.line(),
                        EXPOSURE + "=*: по HTTP доступны все служебные адреса Actuator, включая /env (переменные"
                                + " окружения с паролями), /heapdump (дамп памяти) и /shutdown; перечислите только"
                                + " нужные: health, info, prometheus"))
                .stream()
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }
}
