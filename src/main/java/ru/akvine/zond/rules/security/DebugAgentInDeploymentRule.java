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
import ru.akvine.zond.rules.files.JavaVersions;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code -agentlib:jdwp=transport=dt_socket,server=y,address=*:5005} в настройках запуска: протокол отладки JDWP
 * не знает ни аутентификации, ни шифрования. Тот, кто дотянулся до порта, останавливает потоки, читает память
 * и вызывает любые методы - то есть выполняет в JVM свой код.
 */
@Component
public class DebugAgentInDeploymentRule extends AbstractContextRule {
    // -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 и старая запись -Xrunjdwp:...
    private static final Pattern AGENT = Pattern.compile("-(?:agentlib:jdwp=|Xrunjdwp:)([\\w=.,:*\\[\\]-]+)");
    private static final Pattern SERVER = Pattern.compile("(^|,)server=y(,|$)");
    private static final Pattern ADDRESS = Pattern.compile("(?:^|,)address=([^,]+)");
    private static final Pattern PORT_ONLY = Pattern.compile(":?\\d+");
    private static final Pattern ALL_INTERFACES = Pattern.compile("(\\*|0\\.0\\.0\\.0|\\[::]|\\[::0]):\\d+");
    // С Java 9 адрес из одного порта означает "только этот компьютер"
    private static final int LOCAL_BY_DEFAULT_SINCE = 9;

    @Override
    public String code() {
        return RuleCodes.DEBUG_AGENT_IN_DEPLOYMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует Dockerfile, docker-compose, манифесты Kubernetes и values-файлы Helm и ищет отладочный"
                + " агент JDWP, который принимает подключения с других машин";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        OptionalInt java = JavaVersions.lowest(context.folderFiles());
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!Deployments.isDeployment(file)) {
                continue;
            }
            for (Deployments.Line line : Deployments.lines(file)) {
                Matcher agent = AGENT.matcher(line.text());
                while (agent.find()) {
                    check(file, line.number(), agent.group(1), java, violations);
                }
            }
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

    private void check(TextFile file, int line, String options, OptionalInt java, List<Violation> violations) {
        Matcher address = ADDRESS.matcher(options);
        // server=n - JVM сама подключается к отладчику; без адреса агент порт не открывает
        if (!SERVER.matcher(options).find() || !address.find()) {
            return;
        }
        String where = address.group(1);
        boolean everywhere = ALL_INTERFACES.matcher(where).matches();
        boolean portOnly = PORT_ONLY.matcher(where).matches();
        // address=localhost:5005 и address=5005 на Java 9+ доступны только с самой машины
        if (!everywhere && !(portOnly && (java.isEmpty() || java.getAsInt() < LOCAL_BY_DEFAULT_SINCE))) {
            return;
        }
        String scope = everywhere
                ? "принимает подключения со всех сетевых интерфейсов (address=" + where + ")"
                : "задан одним портом (address=" + where + "): до Java 9 это все сетевые интерфейсы, с Java 9 -"
                + " только сама машина";
        Violation violation = violation(file.path(), line,
                "В настройках запуска включен отладочный агент JDWP, и он " + scope + ". Протокол отладки не знает"
                        + " ни аутентификации, ни шифрования: тот, кто дотянется до порта, выполнит в JVM"
                        + " произвольный код. Уберите агент из настроек, с которыми приложение разворачивают;"
                        + " для разовой отладки привязывайте его к localhost и пробрасывайте порт"
                        + " (kubectl port-forward, ssh -L)");
        if (everywhere && !Deployments.isDevelopment(file.path())) {
            violations.add(violation.withLevel(ErrorLevel.CRITICAL));
        } else {
            // Стенд разработчика либо версия Java, от которой зависит ответ, неизвестна
            boolean unknownJava = portOnly && java.isEmpty();
            violations.add(Deployments.isDevelopment(file.path()) || unknownJava
                    ? violation.withConfidence(Confidence.SUSPICION)
                    : violation);
        }
    }
}
