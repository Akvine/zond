package ru.akvine.zond.rules.codesmell;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;

@Component
public class HardcodedActiveProfileRule extends AbstractConfigRule {
    private static final String ACTIVE_PROFILE = "spring.profiles.active";
    private static final String PLACEHOLDER = "${";

    @Override
    public String code() {
        return RuleCodes.HARDCODED_ACTIVE_PROFILE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет активный профиль, записанный прямо в файле";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        // ${SPRING_PROFILE:dev} берет профиль из окружения - так и нужно
        return configFile.find(ACTIVE_PROFILE)
                .filter(property -> !property.value().isBlank() && !property.value().contains(PLACEHOLDER))
                .map(property -> violation(configFile, property.line(),
                        ACTIVE_PROFILE + "=" + property.value() + " записан в файле: одна и та же сборка"
                                + " запустится с этим профилем в любой среде, включая рабочую; задавайте профиль"
                                + " снаружи - переменной SPRING_PROFILES_ACTIVE или аргументом запуска"))
                .stream()
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
