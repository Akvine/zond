package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.parsers.YamlNode;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckKubernetesNoProbesRule extends AbstractContextRule {
    private static final String READINESS = "readinessProbe";
    private static final String LIVENESS = "livenessProbe";
    private static final String NAME = "name";

    @Override
    public String code() {
        return RuleCodes.CHECK_KUBERNETES_NO_PROBES_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует манифесты Kubernetes и ищет контейнеры без проверок готовности и работоспособности";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isKubernetes(file)) {
                continue;
            }
            for (Kubernetes.Workload workload : Kubernetes.workloads(file)) {
                // Задачам (Job, CronJob) проверки не нужны: они отработали и завершились
                if (!workload.isLongRunning()) {
                    continue;
                }
                for (YamlNode container : workload.containers()) {
                    boolean hasReadiness = container.get(READINESS).exists();
                    boolean hasLiveness = container.get(LIVENESS).exists();
                    if (!hasReadiness || !hasLiveness) {
                        violations.add(violation(file.path(), container.line(),
                                "У контейнера '" + container.get(NAME).text() + "' (" + workload.kind() + " "
                                        + workload.name() + ") нет " + missing(hasReadiness, hasLiveness)
                                        + ": запросы пойдут на под, который еще не запустился, а зависший"
                                        + " контейнер никто не перезапустит"));
                    }
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
        return ErrorType.LOGICAL;
    }

    private String missing(boolean hasReadiness, boolean hasLiveness) {
        if (!hasReadiness && !hasLiveness) {
            return "ни readinessProbe, ни livenessProbe";
        }
        return hasReadiness ? LIVENESS : READINESS;
    }
}
