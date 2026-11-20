package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Optional;

@Component
public class ActuatorExposureRule extends AbstractConfigRule {
    private static final String EXPOSURE = "management.endpoints.web.exposure.include";
    private static final List<String> MANAGEMENT_ENDPOINT_KEYS =
            List.of("management.server.port", "management.server.address");
    private static final String ALL = "*";

    @Override
    public String code() {
        return RuleCodes.ACTUATOR_EXPOSURE_RULE_CODE;
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
        Optional<ConfigProperty> exposure = configFile.find(EXPOSURE)
                .filter(property -> property.value().contains(ALL) && !configFile.isNonProduction(property));
        if (exposure.isEmpty()) {
            return List.of();
        }
        Violation violation = violation(configFile, exposure.get().line(),
                EXPOSURE + "=*: по HTTP доступны все служебные адреса Actuator, включая /env (переменные"
                        + " окружения с паролями), /heapdump (дамп памяти) и /shutdown; перечислите только"
                        + " нужные: health, info, prometheus");
        // management.server.port / management.server.address: служебные адреса вынесены на отдельный порт или
        // слушают только свой узел. Снаружи их обычно не видно, но это зависит от сети, поэтому подозрение
        boolean separated = MANAGEMENT_ENDPOINT_KEYS.stream().anyMatch(key -> configFile.find(key).isPresent());
        return List.of(separated ? violation.withConfidence(Confidence.SUSPICION) : violation);
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
