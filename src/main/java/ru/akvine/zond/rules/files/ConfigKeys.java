package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ключи настроек Spring: сравнение без учета способа записи, свои и чужие ключи, профиль файла
 */
@UtilityClass
public class ConfigKeys {
    // Настройки самого Spring и распространенных библиотек: их читает не код проекта
    private static final List<String> FRAMEWORK_PREFIXES = List.of(
            "spring.", "server.", "management.", "logging.", "debug", "trace", "info.", "eureka.", "feign.",
            "resilience4j.", "springdoc.", "swagger.", "hibernate.", "jakarta.", "javax.", "mybatis.", "camel.",
            "micrometer.", "otel.", "sentry.", "zuul.", "ribbon.", "hystrix.", "vaadin.", "grpc.", "kafka.",
            "flyway.", "liquibase.", "jasypt.", "banner.", "application.");

    // application-dev.properties -> application и dev; application.yml -> application и пустой профиль
    private static final Pattern CONFIG_NAME = Pattern.compile("^([A-Za-z]+?)(?:-([\\w-]+))?\\.(properties|ya?ml)$");

    /**
     * Spring не различает max-size, max_size и maxSize - приводим ключ к одному виду
     */
    public String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    public boolean isFramework(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return FRAMEWORK_PREFIXES.stream().anyMatch(prefix -> lower.equals(prefix) || lower.startsWith(prefix));
    }

    /**
     * @return имя без профиля и расширения: application
     */
    public String baseName(Path file) {
        Matcher matcher = CONFIG_NAME.matcher(file.getFileName().toString());
        return matcher.matches() ? matcher.group(1) : file.getFileName().toString();
    }

    /**
     * @return профиль из имени файла либо пустая строка для основного файла
     */
    public String profile(Path file) {
        Matcher matcher = CONFIG_NAME.matcher(file.getFileName().toString());
        return matcher.matches() && matcher.group(2) != null ? matcher.group(2) : "";
    }
}
