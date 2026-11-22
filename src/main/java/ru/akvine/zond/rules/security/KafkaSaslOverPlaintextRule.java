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
import java.util.Optional;

/**
 * {@code security.protocol=SASL_PLAINTEXT}: брокер Kafka требует имя и пароль, но соединение не шифруется. С
 * механизмом PLAIN пароль идет по сети как есть; со SCRAM пароль не раскрывается, но сами сообщения читает
 * каждый, кто видит трафик. {@code SASL_SSL} закрывает и то, и другое.
 */
@Component
public class KafkaSaslOverPlaintextRule extends AbstractConfigRule {
    private static final String SECURITY_PROTOCOL = "security.protocol";
    // spring.kafka.security.protocol и spring.kafka.properties.security.protocol: один и тот же параметр клиента
    private static final String SECURITY_PROTOCOL_DASHED = "security-protocol";
    private static final String SASL_PLAINTEXT = "sasl_plaintext";
    private static final String SASL_MECHANISM = "sasl.mechanism";
    private static final String SASL_MECHANISM_DASHED = "sasl-mechanism";
    private static final String PLAIN = "plain";

    @Override
    public String code() {
        return RuleCodes.KAFKA_SASL_OVER_PLAINTEXT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет подключение к Kafka с паролем, но без шифрования:"
                + " security.protocol=SASL_PLAINTEXT";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        List<Violation> violations = new ArrayList<>();
        for (ConfigProperty property : configFile.productionProperties()) {
            String key = property.key().toLowerCase(Locale.ROOT);
            boolean isProtocol = key.endsWith(SECURITY_PROTOCOL) || key.endsWith(SECURITY_PROTOCOL_DASHED);
            if (!isProtocol || !SASL_PLAINTEXT.equals(property.value().trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            // Механизм того же клиента: ключ с тем же началом
            String prefix = property.key().substring(0, property.key().length() - SECURITY_PROTOCOL.length());
            Optional<String> mechanism = configFile.productionProperties().stream()
                    .filter(other -> other.key().startsWith(prefix) && isMechanism(other.key()))
                    .map(other -> other.value().trim())
                    .findFirst();
            boolean passwordInClear = mechanism.filter(name -> PLAIN.equals(name.toLowerCase(Locale.ROOT))).isPresent();
            Violation violation = violation(configFile, property.line(),
                    "'" + property.key() + "=" + property.value().trim() + "': подключение к Kafka с паролем, но без"
                            + " шифрования. " + (passwordInClear
                            ? "Механизм PLAIN передает имя и пароль по сети открытым текстом - их прочитает любой,"
                            + " кто видит трафик"
                            : "Пароль " + mechanism.map(name -> "(" + name + ") ").orElse("") + "при этом может и не"
                            + " раскрываться, но сами сообщения идут открытым текстом, и их можно читать и"
                            + " подменять в пути")
                            + "; используйте SASL_SSL");
            violations.add(passwordInClear ? violation.withLevel(ErrorLevel.MAJOR) : violation);
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    private boolean isMechanism(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.endsWith(SASL_MECHANISM) || lower.endsWith(SASL_MECHANISM_DASHED);
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }
}
