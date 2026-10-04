package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.parsers.YamlNode;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ComposeFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class CheckComposePrivilegedServiceRule extends AbstractContextRule {
    private static final String TRUE = "true";
    private static final String HOST = "host";
    private static final String DOCKER_SOCKET = "docker.sock";
    private static final Set<String> DANGEROUS_CAPABILITIES = Set.of("ALL", "SYS_ADMIN");

    @Override
    public String code() {
        return RuleCodes.CHECK_COMPOSE_PRIVILEGED_SERVICE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует docker-compose и ищет сервисы с лишними правами: privileged, сеть хоста, доступ к docker.sock";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            ComposeFiles.services(file).forEach((name, service) -> check(file, name, service, violations));
        }
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

    private void check(TextFile file, String name, YamlNode service, List<Violation> violations) {
        if (service.get("privileged").is(TRUE)) {
            report(file, service.get("privileged"), name, "запущен с privileged: true и получает все права хоста", violations);
        }
        if (service.get("network_mode").is(HOST)) {
            report(file, service.get("network_mode"), name, "работает в сети хоста и видит все его порты", violations);
        }
        if (service.get("pid").is(HOST)) {
            report(file, service.get("pid"), name, "видит все процессы хоста (pid: host)", violations);
        }
        for (YamlNode capability : service.get("cap_add").items()) {
            if (DANGEROUS_CAPABILITIES.contains(capability.text().toUpperCase(Locale.ROOT))) {
                report(file, capability, name, "получает права " + capability.text() + " (cap_add)", violations);
            }
        }
        for (YamlNode volume : service.get("volumes").items()) {
            // Короткая запись - строка "/var/run/docker.sock:/var/run/docker.sock", длинная - словарь с source
            String source = volume.isScalar() ? volume.text() : volume.get("source").text();
            if (source.contains(DOCKER_SOCKET)) {
                report(file, volume, name, "получает docker.sock и может управлять всеми контейнерами хоста", violations);
            }
        }
    }

    private void report(TextFile file, YamlNode node, String service, String problem, List<Violation> violations) {
        violations.add(violation(file.path(), node.line(),
                "Сервис '" + service + "' " + problem + ": взлом такого контейнера равен взлому всего сервера;"
                        + " уберите настройку либо выдайте только те права, без которых сервис не работает"));
    }
}
