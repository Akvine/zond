package ru.akvine.zond.rules.performance;

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
import java.util.Optional;

@Component
public class MissingLimitsInConfigRule extends AbstractConfigRule {
    private static final String DATASOURCE_URL = "spring.datasource.url";
    private static final String POOL_SIZE = "spring.datasource.hikari.maximum-pool-size";
    private static final String MULTIPART_PREFIX = "spring.servlet.multipart.";
    private static final String MAX_FILE_SIZE = "spring.servlet.multipart.max-file-size";

    @Override
    public String code() {
        return RuleCodes.MISSING_LIMITS_IN_CONFIG_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет незаданные пределы: размер пула соединений и загружаемого файла";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        List<Violation> violations = new ArrayList<>();
        if (configFile.isNonProduction()) {
            return violations;
        }

        Optional<ConfigProperty> datasource = configFile.find(DATASOURCE_URL);
        if (datasource.isPresent() && configFile.find(POOL_SIZE).isEmpty()) {
            violations.add(violation(configFile, datasource.get().line(),
                    "Источник данных настроен, а размер пула (" + POOL_SIZE + ") не задан: действует"
                            + " значение по умолчанию - 10 соединений, которое редко подходит и приложению,"
                            + " и базе; задайте его под нагрузку и лимит соединений БД"));
        }

        // Загрузка файлов настраивается, а предел размера оставлен по умолчанию
        Optional<ConfigProperty> multipart = configFile.properties().stream()
                .filter(property -> property.key().startsWith(MULTIPART_PREFIX))
                .findFirst();
        if (multipart.isPresent() && configFile.find(MAX_FILE_SIZE).isEmpty()) {
            violations.add(violation(configFile, multipart.get().line(),
                    "Загрузка файлов настроена, а предел размера (" + MAX_FILE_SIZE + ") не задан:"
                            + " действует значение по умолчанию - 1 МБ; задайте предел явно"));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
