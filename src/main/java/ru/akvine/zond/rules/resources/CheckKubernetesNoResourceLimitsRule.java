package ru.akvine.zond.rules.resources;

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
public class CheckKubernetesNoResourceLimitsRule extends AbstractContextRule {
    private static final String RESOURCES = "resources";
    private static final String REQUESTS = "requests";
    private static final String LIMITS = "limits";
    private static final String NAME = "name";

    @Override
    public String code() {
        return RuleCodes.CHECK_KUBERNETES_NO_RESOURCE_LIMITS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует манифесты Kubernetes и ищет контейнеры без запросов и лимитов памяти и процессора";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isKubernetes(file)) {
                continue;
            }
            for (Kubernetes.Workload workload : Kubernetes.workloads(file)) {
                for (YamlNode container : workload.containers()) {
                    YamlNode resources = container.get(RESOURCES);
                    boolean hasRequests = resources.get(REQUESTS).isMap();
                    boolean hasLimits = resources.get(LIMITS).isMap();
                    if (!hasRequests || !hasLimits) {
                        violations.add(violation(file.path(), container.line(),
                                "У контейнера '" + container.get(NAME).text() + "' (" + workload.kind() + " "
                                        + workload.name() + ") не заданы " + missing(hasRequests, hasLimits)
                                        + ": без запросов планировщик размещает под вслепую, без лимитов один"
                                        + " контейнер может занять память всего узла; задайте resources.requests"
                                        + " и resources.limits"));
                    }
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
        return ErrorType.RESOURCE;
    }

    private String missing(boolean hasRequests, boolean hasLimits) {
        if (!hasRequests && !hasLimits) {
            return "ни запросы, ни лимиты ресурсов";
        }
        return hasRequests ? "лимиты ресурсов" : "запросы ресурсов";
    }
}
