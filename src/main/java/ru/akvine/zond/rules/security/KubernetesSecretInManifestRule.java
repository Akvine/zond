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
import ru.akvine.zond.rules.files.Kubernetes;
import ru.akvine.zond.rules.files.TextFiles;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class KubernetesSecretInManifestRule extends AbstractContextRule {
    private static final String KIND = "kind";
    private static final String SECRET = "Secret";
    private static final String CONFIG_MAP = "ConfigMap";
    private static final String NAME = "name";
    private static final String VALUE = "value";
    private static final Set<String> DATA_BLOCKS = Set.of("data", "stringData");

    @Override
    public String code() {
        return RuleCodes.KUBERNETES_SECRET_IN_MANIFEST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует манифесты Kubernetes и ищет пароли и ключи, записанные открытым текстом в env, ConfigMap и Secret";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isKubernetes(file)) {
                continue;
            }
            for (Kubernetes.Workload workload : Kubernetes.workloads(file)) {
                for (YamlNode container : workload.allContainers()) {
                    for (YamlNode variable : container.get("env").items()) {
                        // valueFrom.secretKeyRef - правильный способ: значения в манифесте нет
                        check(file, variable.get(NAME).text(), variable.get(VALUE), violations);
                    }
                }
            }
            for (YamlNode document : Kubernetes.documents(file)) {
                checkData(file, document, violations);
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

    private void checkData(TextFile file, YamlNode document, List<Violation> violations) {
        String kind = document.get(KIND).text();
        if (SECRET.equals(kind)) {
            // Значения в data закодированы base64, а не зашифрованы: в репозитории секрет читается так же легко
            for (String block : DATA_BLOCKS) {
                document.get(block).entries().forEach((key, value) -> {
                    if (isLiteral(value.text())) {
                        violations.add(violation(file.path(), value.line(),
                                "Значение '" + key + "' объекта Secret записано в манифесте и попадет в репозиторий:"
                                        + " base64 - это кодировка, а не шифрование; храните секреты в хранилище"
                                        + " (Vault, Sealed Secrets, External Secrets) и подставляйте при развертывании"));
                    }
                });
            }
        } else if (CONFIG_MAP.equals(kind)) {
            document.get("data").entries().forEach((key, value) -> check(file, key, value, violations));
        }
    }

    private void check(TextFile file, String name, YamlNode value, List<Violation> violations) {
        if (Secrets.isSecretName(name) && isLiteral(value.text()) && Secrets.isSecretValue(value.text())) {
            violations.add(violation(file.path(), value.line(),
                    "Секрет '" + name + "' записан в манифесте открытым текстом и попадет в репозиторий;"
                            + " вынесите его в Secret и подключите через valueFrom.secretKeyRef"));
        }
    }

    // Подстановка шаблонизатора (${PASSWORD}, {{ .Values.password }}) - не само значение
    private boolean isLiteral(String value) {
        return !value.isBlank() && !value.contains("$") && !value.contains("{{");
    }
}
