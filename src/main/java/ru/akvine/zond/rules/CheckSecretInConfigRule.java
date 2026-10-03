package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckSecretInConfigRule extends AbstractConfigRule {
    // ${DB_PASSWORD}, ${db.password:}: значение приходит из окружения
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{.*}$");

    // ENC(...), {cipher}...: значение зашифровано
    private static final Pattern ENCRYPTED = Pattern.compile("^(ENC\\(.*\\)|\\{cipher}.*)$");

    private static final Set<String> NOT_SECRET_VALUES = Set.of("true", "false", "null", "none", "changeme", "");

    @Override
    public String code() {
        return RuleCodes.CHECK_SECRET_IN_CONFIG_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет пароли, токены и ключи, записанные открытым текстом";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        // В профилях разработки и тестов учетные данные обычно ненастоящие
        if (configFile.isNonProduction()) {
            return List.of();
        }
        return configFile.properties().stream()
                .filter(this::isPlainSecret)
                .map(property -> violation(configFile, property.line(),
                        "Секрет '" + property.key() + "' записан в файле настроек открытым текстом: файл попадает"
                                + " в репозиторий и в сборку, значение увидит каждый, у кого есть к ним доступ;"
                                + " подставляйте его из окружения: ${ИМЯ_ПЕРЕМЕННОЙ}"))
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

    private boolean isPlainSecret(ConfigProperty property) {
        String value = property.value().trim();
        return Secrets.isSecretName(lastSegment(property.key()))
                && !NOT_SECRET_VALUES.contains(value.toLowerCase())
                && !PLACEHOLDER.matcher(value).matches()
                && !ENCRYPTED.matcher(value).matches();
    }

    // spring.datasource.password -> password: о секрете говорит последняя часть ключа
    private String lastSegment(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }
}
