package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.Deployments;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Удаленный JMX ({@code -Dcom.sun.management.jmxremote.port=...}) с отключенной защитой. Без аутентификации
 * любой, кто дошел до порта, управляет приложением через MBean-ы вплоть до загрузки своего кода; без TLS имя
 * и пароль JMX идут по сети открытым текстом.
 */
@Component
public class JmxRemoteWithoutProtectionRule extends AbstractContextRule {
    private static final String PREFIX = "com.sun.management.jmxremote.";
    // Порт задан - значит, JMX принимает подключения по сети; без него он доступен только локально
    private static final Pattern REMOTE_PORT =
            Pattern.compile(Pattern.quote(PREFIX) + "(rmi\\.)?port\\s*[=:]\\s*\"?\\d");
    private static final Pattern NO_AUTHENTICATION =
            Pattern.compile(Pattern.quote(PREFIX) + "authenticate\\s*[=:]\\s*\"?false\\b");
    private static final Pattern NO_TLS = Pattern.compile(Pattern.quote(PREFIX) + "ssl\\s*[=:]\\s*\"?false\\b");

    @Override
    public String code() {
        return RuleCodes.JMX_REMOTE_WITHOUT_PROTECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует Dockerfile, docker-compose, манифесты Kubernetes и values-файлы Helm и ищет удаленный JMX"
                + " без аутентификации или без шифрования";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!Deployments.isDeployment(file)) {
                continue;
            }
            List<Deployments.Line> lines = Deployments.lines(file);
            if (lines.stream().noneMatch(line -> REMOTE_PORT.matcher(line.text()).find())) {
                continue;
            }
            Optional<Deployments.Line> open = first(lines, NO_AUTHENTICATION);
            Optional<Deployments.Line> plain = first(lines, NO_TLS);
            if (open.isPresent()) {
                add(file, violation(file.path(), open.get().number(),
                        "Удаленный JMX включен без аутентификации (jmxremote.authenticate=false): любой, кто дойдет"
                                + " до порта, управляет приложением через MBean-ы - меняет настройки, снимает дампы"
                                + " памяти, загружает свой код; включите аутентификацию и TLS либо оставьте JMX"
                                + " доступным только локально").withLevel(ErrorLevel.CRITICAL), violations);
            } else if (plain.isPresent()) {
                add(file, violation(file.path(), plain.get().number(),
                        "Удаленный JMX работает без TLS (jmxremote.ssl=false): имя и пароль JMX и все данные идут"
                                + " по сети открытым текстом, их прочитает любой, кто видит трафик; включите"
                                + " jmxremote.ssl=true с хранилищем ключей либо не открывайте порт JMX за пределы"
                                + " пода"), violations);
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

    // На стенде разработчика JMX без защиты - обычное дело
    private void add(TextFile file, Violation violation, List<Violation> violations) {
        violations.add(Deployments.isDevelopment(file.path())
                ? violation.withConfidence(Confidence.SUSPICION)
                : violation);
    }

    private Optional<Deployments.Line> first(List<Deployments.Line> lines, Pattern pattern) {
        return lines.stream().filter(line -> pattern.matcher(line.text()).find()).findFirst();
    }
}
