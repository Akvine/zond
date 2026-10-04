package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.parsers.YamlNode;
import ru.akvine.zond.parsers.YamlParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Манифесты Kubernetes: объекты, которые запускают контейнеры, и сами контейнеры
 */
@UtilityClass
public class Kubernetes {
    private static final String KIND = "kind";
    private static final String SPEC = "spec";
    private static final String TEMPLATE = "template";
    private static final String JOB_TEMPLATE = "jobTemplate";
    private static final String METADATA = "metadata";
    private static final String NAME = "name";
    private static final String CONTAINERS = "containers";
    private static final String INIT_CONTAINERS = "initContainers";
    private static final String POD = "Pod";
    private static final String CRON_JOB = "CronJob";

    // Объекты с шаблоном пода в spec.template
    private static final Set<String> TEMPLATE_KINDS =
            Set.of("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "ReplicationController");

    // Работают постоянно и принимают запросы: им нужны проверки готовности
    private static final Set<String> LONG_RUNNING_KINDS = Set.of("Deployment", "StatefulSet", "DaemonSet");

    /**
     * Объект, запускающий контейнеры
     *
     * @param podSpec описание пода: контейнеры, тома, настройки безопасности
     */
    public record Workload(String kind, String name, YamlNode podSpec) {

        public boolean isLongRunning() {
            return LONG_RUNNING_KINDS.contains(kind);
        }

        /**
         * @return основные контейнеры, без подготовительных
         */
        public List<YamlNode> containers() {
            return podSpec.get(CONTAINERS).items();
        }

        public List<YamlNode> allContainers() {
            List<YamlNode> all = new ArrayList<>(podSpec.get(INIT_CONTAINERS).items());
            all.addAll(containers());
            return all;
        }
    }

    /**
     * @return документы манифеста; в одном файле их может быть несколько
     */
    public List<YamlNode> documents(TextFile file) {
        return YamlParser.parse(file.lines()).orElse(List.of());
    }

    public List<Workload> workloads(TextFile file) {
        List<Workload> workloads = new ArrayList<>();
        for (YamlNode document : documents(file)) {
            String kind = document.get(KIND).text();
            YamlNode podSpec = podSpecOf(kind, document);
            if (podSpec.exists()) {
                workloads.add(new Workload(kind, document.get(METADATA).get(NAME).text(), podSpec));
            }
        }
        return workloads;
    }

    private YamlNode podSpecOf(String kind, YamlNode document) {
        YamlNode spec = document.get(SPEC);
        if (POD.equals(kind)) {
            return spec;
        }
        if (CRON_JOB.equals(kind)) {
            return spec.get(JOB_TEMPLATE).get(SPEC).get(TEMPLATE).get(SPEC);
        }
        return TEMPLATE_KINDS.contains(kind) ? spec.get(TEMPLATE).get(SPEC) : YamlNode.missing();
    }
}
