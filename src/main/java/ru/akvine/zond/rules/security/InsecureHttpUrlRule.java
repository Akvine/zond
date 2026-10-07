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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InsecureHttpUrlRule extends AbstractConfigRule {
    private static final Pattern HTTP_URL = Pattern.compile("\\bhttp://([A-Za-z0-9.\\-]+)", Pattern.CASE_INSENSITIVE);

    // Адреса внутри машины, контейнерной сети и кластера: шифрование там обычно снимается на входе
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "host.docker.internal", "0.0.0.0");
    private static final Set<String> INTERNAL_ZONES = Set.of(
            "local", "internal", "localhost", "lan", "svc", "cluster", "test", "example", "invalid", "intranet", "corp");
    private static final Pattern PRIVATE_ADDRESS = Pattern.compile(
            "(127|10)\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+|169\\.254\\.\\d+\\.\\d+");
    // Не адреса для обращения, а идентификаторы: пространства имен XML, схемы
    private static final Set<String> IDENTIFIER_HOSTS = Set.of(
            "www.w3.org", "xmlns.jcp.org", "java.sun.com", "maven.apache.org", "www.springframework.org",
            "schemas.xmlsoap.org", "json-schema.org", "www.liquibase.org");
    private static final Set<String> EXAMPLE_DOMAINS = Set.of("example.com", "example.org", "example.net");

    @Override
    public String code() {
        return RuleCodes.INSECURE_HTTP_URL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет адреса внешних сервисов по http:// вместо https://";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        List<Violation> violations = new ArrayList<>();
        if (configFile.isNonProduction()) {
            return violations;
        }
        for (ConfigProperty property : configFile.properties()) {
            Matcher url = HTTP_URL.matcher(property.value());
            while (url.find()) {
                String host = url.group(1).toLowerCase(Locale.ROOT);
                if (isExternal(host)) {
                    violations.add(violation(configFile, property.line(),
                            "Свойство '" + property.key() + "' обращается к '" + host + "' по http://: данные и"
                                    + " учетные записи идут открытым текстом, и их можно прочитать и подменить по"
                                    + " пути; используйте https://"));
                    break;
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // Внешним считается полное доменное имя либо публичный IP-адрес
    private boolean isExternal(String host) {
        if (LOCAL_HOSTS.contains(host) || IDENTIFIER_HOSTS.contains(host) || PRIVATE_ADDRESS.matcher(host).matches()) {
            return false;
        }
        // Имя без точки - это сервис в сети контейнеров или кластера: http://orders:8080
        int lastDot = host.lastIndexOf('.');
        if (lastDot < 0) {
            return false;
        }
        boolean example = EXAMPLE_DOMAINS.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        return !example && !INTERNAL_ZONES.contains(host.substring(lastDot + 1));
    }
}
