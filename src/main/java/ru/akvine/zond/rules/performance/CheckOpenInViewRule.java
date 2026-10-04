package ru.akvine.zond.rules.performance;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class CheckOpenInViewRule extends AbstractConfigRule {
    private static final String OPEN_IN_VIEW = "spring.jpa.open-in-view";
    private static final String ENABLED = "true";

    // Основной файл настроек, без профиля: application.properties, application.yml
    private static final Pattern MAIN_CONFIG = Pattern.compile("^application\\.(properties|yml|yaml)$");

    // Признак того, что приложение вообще работает с БД через JPA
    private static final List<String> JPA_PREFIXES = List.of("spring.jpa.", "spring.datasource.");

    @Override
    public String code() {
        return RuleCodes.CHECK_OPEN_IN_VIEW_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет включенный Open Session In View (spring.jpa.open-in-view)";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        String message = "сессия Hibernate остается открытой до конца HTTP-запроса - LAZY-связи молча"
                + " подгружаются из контроллера и при сериализации ответа, отдельным запросом каждая, а соединение"
                + " с БД занято все время запроса; задайте " + OPEN_IN_VIEW + "=false";

        Optional<ConfigProperty> property = configFile.find(OPEN_IN_VIEW);
        if (property.isPresent()) {
            return ENABLED.equalsIgnoreCase(property.get().value())
                    ? List.of(violation(configFile, property.get().line(), OPEN_IN_VIEW + "=true: " + message))
                    : List.of();
        }

        // Свойство не задано, а по умолчанию оно включено. Сообщаем один раз - для основного файла
        boolean isMainConfig = MAIN_CONFIG.matcher(configFile.path().getFileName().toString()).matches();
        boolean usesJpa = configFile.properties().stream()
                .anyMatch(candidate -> JPA_PREFIXES.stream().anyMatch(candidate.key()::startsWith));
        return isMainConfig && usesJpa && !configFile.isNonProduction()
                ? List.of(violation(configFile, OPEN_IN_VIEW + " не задано, а по умолчанию оно включено: " + message))
                : List.of();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
