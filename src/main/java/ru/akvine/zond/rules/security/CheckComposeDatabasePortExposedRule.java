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
import java.util.Map;
import java.util.Set;

@Component
public class CheckComposeDatabasePortExposedRule extends AbstractContextRule {
    // Порт контейнера -> что на нем обычно работает
    private static final Map<String, String> INTERNAL_PORTS = Map.ofEntries(
            Map.entry("5432", "PostgreSQL"), Map.entry("3306", "MySQL"), Map.entry("1433", "SQL Server"),
            Map.entry("1521", "Oracle"), Map.entry("27017", "MongoDB"), Map.entry("6379", "Redis"),
            Map.entry("9200", "Elasticsearch"), Map.entry("5672", "RabbitMQ"), Map.entry("9092", "Kafka"),
            Map.entry("2181", "ZooKeeper"), Map.entry("11211", "Memcached"), Map.entry("9042", "Cassandra"));
    private static final Set<String> ALL_INTERFACES = Set.of("", "0.0.0.0", "::", "[::]");
    private static final int HOST_AND_CONTAINER = 2;

    @Override
    public String code() {
        return RuleCodes.CHECK_COMPOSE_DATABASE_PORT_EXPOSED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует docker-compose и ищет порты баз данных и брокеров, открытые на всех сетевых интерфейсах";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            ComposeFiles.services(file).forEach((name, service) -> {
                for (YamlNode port : service.get("ports").items()) {
                    String containerPort = exposedContainerPort(port);
                    if (INTERNAL_PORTS.containsKey(containerPort)) {
                        violations.add(violation(file.path(), port.line(),
                                "Порт " + containerPort + " (" + INTERNAL_PORTS.get(containerPort) + ") сервиса '"
                                        + name + "' открыт на всех сетевых интерфейсах: к нему можно подключиться"
                                        + " снаружи, а Docker обходит правила файрвола; привяжите порт к 127.0.0.1"
                                        + " либо уберите публикацию - сервисы в одной сети видят друг друга и так"));
                    }
                }
            });
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

    /**
     * @return порт контейнера, если он опубликован на всех интерфейсах хоста; иначе пустая строка
     */
    private String exposedContainerPort(YamlNode port) {
        if (!port.isScalar()) {
            // Длинная запись: target, published, host_ip
            boolean isPublished = port.get("published").exists() && ALL_INTERFACES.contains(port.get("host_ip").text());
            return isPublished ? port.get("target").text() : "";
        }
        // "5432:5432", "127.0.0.1:5432:5432", "5432:5432/tcp"; один порт без двоеточия получает случайный порт хоста
        String[] parts = port.text().replaceAll("/\\w+$", "").split(":");
        if (parts.length == HOST_AND_CONTAINER) {
            return parts[1];
        }
        boolean isBoundToAll = parts.length > HOST_AND_CONTAINER && ALL_INTERFACES.contains(parts[0]);
        return isBoundToAll ? parts[parts.length - 1] : "";
    }
}
