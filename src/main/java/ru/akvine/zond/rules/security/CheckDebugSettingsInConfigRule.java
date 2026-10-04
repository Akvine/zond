package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class CheckDebugSettingsInConfigRule extends AbstractConfigRule {
    private static final String TRUE = "true";
    private static final Set<String> VERBOSE_LEVELS = Set.of("debug", "trace", "all");
    private static final String ROOT_LEVEL = "logging.level.root";

    // Свойство -> что плохого в том, что оно включено в рабочей среде
    private static final Map<String, String> DEBUG_FLAGS = Map.of(
            "spring.jpa.show-sql", "каждый запрос печатается в лог мимо настроек логирования",
            "spring.h2.console.enabled", "консоль H2 дает любому, кто до нее дойдет, полный доступ к базе",
            "spring.devtools.restart.enabled", "devtools перезапускает приложение и открывает удаленный доступ",
            "debug", "Spring печатает отчет автонастройки и включает подробный лог");

    @Override
    public String code() {
        return RuleCodes.CHECK_DEBUG_SETTINGS_IN_CONFIG_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет отладочные режимы: show-sql, уровень DEBUG, консоль H2, devtools";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        List<Violation> violations = new ArrayList<>();
        // В профилях разработки и тестов отладочные режимы уместны
        if (configFile.isNonProduction()) {
            return violations;
        }
        DEBUG_FLAGS.forEach((key, problem) -> configFile.find(key)
                .filter(property -> TRUE.equalsIgnoreCase(property.value().trim()))
                .ifPresent(property -> violations.add(report(configFile, property, problem))));
        configFile.find(ROOT_LEVEL)
                .filter(property -> VERBOSE_LEVELS.contains(property.value().trim().toLowerCase(Locale.ROOT)))
                .ifPresent(property -> violations.add(report(configFile, property,
                        "в лог попадает все подряд, включая данные запросов, а диск заполняется за часы")));
        violations.sort((first, second) -> Integer.compare(first.line(), second.line()));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private Violation report(ConfigFile configFile, ConfigProperty property, String problem) {
        return violation(configFile, property.line(),
                property.key() + "=" + property.value() + " в рабочих настройках: " + problem
                        + "; включайте это только в профиле разработки");
    }
}
