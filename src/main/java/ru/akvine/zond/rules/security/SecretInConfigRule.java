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
import ru.akvine.zond.rules.files.GitIgnore;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SecretInConfigRule extends AbstractConfigRule {
    // ${DB_PASSWORD}, ${db.password:}: значение приходит из окружения
    // {{ .Values.secret }} подставит Helm, @api.token@ - сборка Maven: секрета в самом файле нет
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{.*}$|^\\{\\{.*}}$|^@[\\w.\\-]+@$");

    // ENC(...), {cipher}..., {cas-cipher}...: значение зашифровано
    private static final Pattern ENCRYPTED = Pattern.compile("^(ENC\\(.*\\)|\\{[\\w-]*cipher}.*)$");

    // refill-tokens: 60, token-ttl: 15m - число или срок, а не секрет. Пароль из цифр - секрет
    private static final Pattern AMOUNT = Pattern.compile("(?i)^-?\\d+(\\.\\d+)?(ms|ns|us|s|m|h|d|kb|mb|gb)?$|^P(T)?[\\d.HMSD]+$");
    private static final Pattern PASSWORD_LIKE = Pattern.compile("(?i).*(pass|pwd|pin|secret|credential).*");

    private static final List<String> USER_KEYS = List.of("username", "user", "login", "user-name");
    private static final Set<String> NOT_SECRET_VALUES = Set.of("true", "false", "null", "none", "changeme", "");

    @Override
    public String code() {
        return RuleCodes.SECRET_IN_CONFIG_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет пароли, токены и ключи, записанные открытым текстом";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        // В профилях разработки и тестов учетные данные обычно ненастоящие: это и отдельные файлы
        // (application-dev.yml), и документы одного файла с условием on-profile
        List<Violation> violations = new ArrayList<>();
        Boolean ignored = null;
        for (ConfigProperty property : configFile.productionProperties()) {
            if (!isPlainSecret(property)) {
                continue;
            }
            // Файл закрыт .gitignore: так секреты и держат вне хранилища. Была ли копия файла добавлена в git до
            // появления этого правила, по папке не видно, поэтому находка остается, но только как подозрение
            if (ignored == null) {
                ignored = GitIgnore.around(configFile.path()).isIgnored(configFile.path());
            }
            if (ignored) {
                violations.add(violation(configFile, property.line(),
                        "Секрет '" + property.key() + "' записан открытым текстом в файле, который закрыт .gitignore:"
                                + " в хранилище он не попадет, если файл не был добавлен в git раньше (проверьте:"
                                + " git ls-files). Файл из каталога ресурсов попадет в сборку, сделанную на этой"
                                + " машине")
                        .withConfidence(Confidence.SUSPICION).withLevel(ErrorLevel.MINOR));
                continue;
            }
            Violation violation = violation(configFile, property.line(),
                    "Секрет '" + property.key() + "' записан в файле настроек открытым текстом: файл попадает"
                            + " в репозиторий и в сборку, значение увидит каждый, у кого есть к ним доступ;"
                            + " подставляйте его из окружения: ${ИМЯ_ПЕРЕМЕННОЙ}");
            // password, guest, postgres либо то же, что и имя пользователя рядом: с такими значениями идут готовые
            // образы баз и брокеров, и для рабочей среды их почти наверняка переопределяют
            violations.add(isDefaultValue(configFile, property)
                    ? violation.withConfidence(Confidence.SUSPICION)
                    : violation);
        }
        return violations;
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
        // "??", "---": без букв и цифр это заглушка, а не секрет
        return Secrets.isSecretName(lastSegment(property.key()))
                && value.chars().anyMatch(Character::isLetterOrDigit)
                && !NOT_SECRET_VALUES.contains(value.toLowerCase())
                && !Secrets.isStub(value)
                && !(AMOUNT.matcher(value).matches() && !PASSWORD_LIKE.matcher(lastSegment(property.key())).matches())
                && !PLACEHOLDER.matcher(value).matches()
                && !ENCRYPTED.matcher(value).matches();
    }

    private boolean isDefaultValue(ConfigFile configFile, ConfigProperty property) {
        String value = property.value().trim();
        if (Secrets.isWellKnownDefault(value)) {
            return true;
        }
        // spring.rabbitmq.username=shop и spring.rabbitmq.password=shop
        int separator = property.key().lastIndexOf('.');
        String prefix = separator < 0 ? "" : property.key().substring(0, separator + 1);
        return USER_KEYS.stream()
                .map(key -> configFile.find(prefix + key))
                .flatMap(Optional::stream)
                .anyMatch(user -> user.value().trim().equalsIgnoreCase(value));
    }

    // spring.datasource.password -> password: о секрете говорит последняя часть ключа
    private String lastSegment(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }
}
