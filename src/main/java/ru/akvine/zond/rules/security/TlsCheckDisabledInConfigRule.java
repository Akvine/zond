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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Проверку сертификата и имени хоста отключают не только кодом, но и настройкой, которую читает библиотека:
 * {@code trust-all=true}, {@code verify-hostname=false}. В коде проекта отключения при этом не видно вовсе, а
 * соединение принимает любой сертификат.
 */
@Component
public class TlsCheckDisabledInConfigRule extends AbstractConfigRule {
    private static final String SEPARATORS = "[-_.]?";
    private static final String START = "(^|.*[-_.])";
    private static final String CHECKED = "(ssl|tls|cert|certificate|hostname)";

    // trust-all, trustAllCerts, trust-self-signed, insecure-skip-verify, skip-ssl-validation,
    // disable-hostname-verification, allow-insecure, invalid-host-name-allowed: проверка отключена, когда true
    private static final Pattern OFF_WHEN_TRUE = Pattern.compile(START + "("
            + "trust" + SEPARATORS + "all(" + SEPARATORS + "(certs|certificates|hosts))?"
            + "|trust" + SEPARATORS + "self" + SEPARATORS + "signed"
            + "|insecure" + SEPARATORS + "(ssl|tls|skip" + SEPARATORS + "verify)"
            + "|skip" + SEPARATORS + CHECKED + SEPARATORS + "(verify|verification|validation|check)"
            + "|disable" + SEPARATORS + CHECKED + SEPARATORS + "(verification|validation|check|checking)"
            + "|allow" + SEPARATORS + "insecure"
            + "|invalid" + SEPARATORS + "host" + SEPARATORS + "name" + SEPARATORS + "allowed"
            + ")");
    // verify-hostname, verify.hostname, hostname-verification, verify-ssl, ssl-verify, validate-certs,
    // validate-server-certificate, checkserveridentity: проверка отключена, когда false
    private static final Pattern OFF_WHEN_FALSE = Pattern.compile(START + "("
            + "verify" + SEPARATORS + "(host" + SEPARATORS + "name|ssl|tls|peer|certs?|certificates?"
            + "|server" + SEPARATORS + "(cert|certificate))"
            + "|(host" + SEPARATORS + "name|ssl|tls|cert|certificate)" + SEPARATORS
            + "(verification|verify|validation)(" + SEPARATORS + "enabled)?"
            + "|validate" + SEPARATORS + "(server" + SEPARATORS + ")?(certs?|certificates?)"
            + "|check" + SEPARATORS + "server" + SEPARATORS + "identity"
            + ")");
    // Выключатель самого шифрования у того же клиента: <клиент>.ssl=false, <клиент>.ssl.enabled=false
    private static final List<String> TLS_SWITCHES = List.of(
            "ssl", "ssl.enabled", "ssl-enabled", "tls", "tls.enabled", "tls-enabled", "use-ssl", "use_ssl", "usessl",
            "use-tls", "use_tls", "usetls");
    private static final Pattern TLS_SECTION = Pattern.compile(".*\\.(ssl|tls)");
    // Выключатель ищем у владельца свойства и на уровень выше: verify.hostname - имя из двух частей
    private static final int OWNER_LEVELS = 2;
    private static final Set<String> TRUE_VALUES = Set.of("true", "yes", "on");
    private static final Set<String> FALSE_VALUES = Set.of("false", "no", "off");

    // Почта: mail.smtp.ssl.trust=* - доверять любому серверу
    private static final Pattern MAIL_TRUST = Pattern.compile(".*mail\\.\\w+\\.ssl\\.trust");
    private static final String ANY_HOST = "*";
    // Kafka: пустое значение отключает сверку имени брокера с сертификатом
    private static final String ENDPOINT_IDENTIFICATION = "ssl.endpoint.identification.algorithm";

    @Override
    public String code() {
        return RuleCodes.TLS_CHECK_DISABLED_IN_CONFIG_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет свойства, которые отключают проверку сертификата или имени хоста:"
                + " trust-all=true, verify-hostname=false и подобные";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        List<Violation> violations = new ArrayList<>();
        Boolean localOnly = null;
        // В настройках разработки и тестов самоподписанный сертификат и отключенная проверка - обычное дело
        for (ConfigProperty property : configFile.productionProperties()) {
            String key = property.key().toLowerCase(Locale.ROOT);
            String value = property.value().trim().toLowerCase(Locale.ROOT);
            boolean hostOnly = key.contains("host") || key.contains("identi");
            boolean disabled = TRUE_VALUES.contains(value) && OFF_WHEN_TRUE.matcher(key).matches()
                    || FALSE_VALUES.contains(value) && OFF_WHEN_FALSE.matcher(key).matches()
                    || ANY_HOST.equals(value) && MAIL_TRUST.matcher(key).matches()
                    || value.isEmpty() && key.endsWith(ENDPOINT_IDENTIFICATION);
            if (disabled && isTlsOff(key, configFile)) {
                // Соединение идет вовсе без TLS: сверять нечего, и о подмене сертификата речи нет
                continue;
            }
            if (disabled) {
                // Файл, закрытый .gitignore, - настройки одной машины: в хранилище и в поставку он не попадает
                if (localOnly == null) {
                    localOnly = GitIgnore.around(configFile.path()).isIgnored(configFile.path());
                }
                Violation violation = violation(configFile, property.line(),
                        "Настройка '" + property.key() + "=" + property.value().trim() + "' отключает проверку "
                                + (hostOnly ? "имени хоста: подойдет сертификат, выданный на любое другое имя"
                                : "сертификата сервера: соединение примет любой сертификат")
                                + ", и тот, кто встанет между приложением и сервером, прочитает и подменит данные;"
                                + " добавьте сертификат сервера в truststore и включите проверку, а отключение"
                                + " оставьте настройкам стенда разработчика");
                violations.add(localOnly ? violation.withConfidence(Confidence.SUSPICION) : violation);
            }
        }
        return violations;
    }

    // spring.redis.ssl=false рядом с spring.redis.verifyPeer=false: шифрование у этого клиента выключено
    private boolean isTlsOff(String key, ConfigFile configFile) {
        List<String> switches = new ArrayList<>();
        String owner = key;
        for (int level = 0; level < OWNER_LEVELS && owner.contains("."); level++) {
            owner = owner.substring(0, owner.lastIndexOf('.'));
            for (String name : TLS_SWITCHES) {
                switches.add(owner + "." + name);
            }
            if (TLS_SECTION.matcher(owner).matches()) {
                switches.add(owner);
                switches.add(owner + ".enabled");
            }
        }
        return configFile.properties().stream()
                .filter(property -> FALSE_VALUES.contains(property.value().trim().toLowerCase(Locale.ROOT)))
                .map(property -> property.key().toLowerCase(Locale.ROOT))
                .anyMatch(switches::contains);
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
