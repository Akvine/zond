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

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckKubernetesPrivilegedContainerRule extends AbstractContextRule {
    private static final String SECURITY_CONTEXT = "securityContext";
    private static final String NAME = "name";
    private static final String TRUE = "true";
    private static final String ROOT_USER = "0";
    private static final String DOCKER_SOCKET = "docker.sock";
    private static final List<String> HOST_NAMESPACES = List.of("hostNetwork", "hostPID", "hostIPC");

    @Override
    public String code() {
        return RuleCodes.CHECK_KUBERNETES_PRIVILEGED_CONTAINER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует манифесты Kubernetes и ищет контейнеры с лишними правами: privileged, root, сеть и процессы узла";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isKubernetes(file)) {
                continue;
            }
            for (Kubernetes.Workload workload : Kubernetes.workloads(file)) {
                check(file, workload, violations);
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

    private void check(TextFile file, Kubernetes.Workload workload, List<Violation> violations) {
        String owner = workload.kind() + " " + workload.name();
        YamlNode podSpec = workload.podSpec();
        for (String namespace : HOST_NAMESPACES) {
            if (podSpec.get(namespace).is(TRUE)) {
                report(file, podSpec.get(namespace), owner, namespace + ": true открывает поду сеть или процессы узла", violations);
            }
        }
        if (podSpec.get(SECURITY_CONTEXT).get("runAsUser").is(ROOT_USER)) {
            report(file, podSpec.get(SECURITY_CONTEXT).get("runAsUser"), owner, "под запускается от root (runAsUser: 0)", violations);
        }
        for (YamlNode volume : podSpec.get("volumes").items()) {
            if (volume.get("hostPath").get("path").text().contains(DOCKER_SOCKET)) {
                report(file, volume, owner, "под получает docker.sock и может управлять контейнерами узла", violations);
            }
        }
        for (YamlNode container : workload.allContainers()) {
            YamlNode security = container.get(SECURITY_CONTEXT);
            String name = "контейнер '" + container.get(NAME).text() + "' ";
            if (security.get("privileged").is(TRUE)) {
                report(file, security.get("privileged"), owner, name + "запущен с privileged: true", violations);
            }
            if (security.get("allowPrivilegeEscalation").is(TRUE)) {
                report(file, security.get("allowPrivilegeEscalation"), owner, name + "может повышать свои права", violations);
            }
            if (security.get("runAsUser").is(ROOT_USER)) {
                report(file, security.get("runAsUser"), owner, name + "запускается от root (runAsUser: 0)", violations);
            }
        }
    }

    private void report(TextFile file, YamlNode node, String owner, String problem, List<Violation> violations) {
        violations.add(violation(file.path(), node.line(),
                owner + ": " + problem + " - взлом такого контейнера дает доступ ко всему узлу кластера;"
                        + " уберите настройку, задайте runAsNonRoot: true и allowPrivilegeEscalation: false"));
    }
}
