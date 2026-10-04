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
public class CheckKubernetesUnpinnedImageRule extends AbstractContextRule {
    private static final String IMAGE = "image";

    @Override
    public String code() {
        return RuleCodes.CHECK_KUBERNETES_UNPINNED_IMAGE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует манифесты Kubernetes и ищет образы контейнеров без тега или с тегом latest";
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
                    YamlNode image = container.get(IMAGE);
                    if (Images.isUnpinned(image.text())) {
                        violations.add(violation(file.path(), image.line(),
                                "Образ '" + image.text() + "' (" + workload.kind() + " " + workload.name()
                                        + ") без точной версии: разные поды одного приложения могут запуститься"
                                        + " с разными образами, а откатиться на прежнюю версию будет нельзя;"
                                        + " укажите конкретный тег"));
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
        return ErrorType.LOGICAL;
    }
}
