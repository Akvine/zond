package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.parsers.YamlNode;
import ru.akvine.zond.parsers.YamlParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Файлы docker-compose: сервисы и их настройки
 */
@UtilityClass
class ComposeFiles {
    private static final String SERVICES = "services";
    private static final String ENVIRONMENT = "environment";
    private static final char ASSIGNMENT = '=';

    /**
     * @return сервисы по именам; пусто, если файл - не docker-compose либо не разбирается
     */
    Map<String, YamlNode> services(TextFile file) {
        Map<String, YamlNode> services = new LinkedHashMap<>();
        if (!TextFiles.isCompose(file)) {
            return services;
        }
        for (YamlNode document : YamlParser.parse(file.lines()).orElse(List.of())) {
            services.putAll(document.get(SERVICES).entries());
        }
        return services;
    }

    /**
     * @return переменные окружения сервиса: записанные и словарем (KEY: value), и списком (- KEY=value).
     * Значение каждой - узел с текстом и строкой файла
     */
    Map<String, YamlNode> environment(YamlNode service) {
        YamlNode environment = service.get(ENVIRONMENT);
        Map<String, YamlNode> variables = new LinkedHashMap<>(environment.entries());
        for (YamlNode item : environment.items()) {
            int assignment = item.text().indexOf(ASSIGNMENT);
            if (assignment > 0) {
                variables.put(item.text().substring(0, assignment), item);
            }
        }
        return variables;
    }

    /**
     * @return значение переменной: у записи списком - часть после "="
     */
    String valueOf(String name, YamlNode variable) {
        String text = variable.text();
        return text.startsWith(name + ASSIGNMENT) ? text.substring(name.length() + 1) : text;
    }
}
