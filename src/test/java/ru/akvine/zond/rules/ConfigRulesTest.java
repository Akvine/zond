package ru.akvine.zond.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.rules.logical.CheckDdlAutoRule;
import ru.akvine.zond.rules.performance.CheckOpenInViewRule;
import ru.akvine.zond.rules.security.CheckActuatorExposureRule;
import ru.akvine.zond.rules.security.CheckSecretInConfigRule;
import ru.akvine.zond.rules.security.CheckStacktraceExposureRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:200 - jr:204: файлы настроек
 */
class ConfigRulesTest {
    @TempDir
    Path dir;

    private List<ConfigFile> configFiles;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(dir.resolve("application.properties"), """
                spring.datasource.url=jdbc:postgresql://localhost/app
                spring.datasource.password=s3cret
                spring.jpa.hibernate.ddl-auto=update
                management.endpoints.web.exposure.include=*
                server.error.include-stacktrace=always
                app.token-uri=https://example.com/token
                app.api-key=${API_KEY}
                """);
        Files.writeString(dir.resolve("application.yml"), """
                spring:
                  jpa:
                    open-in-view: true
                    hibernate:
                      ddl-auto: create   # схема пересоздается
                  datasource:
                    password: "qwerty"
                management:
                  endpoints:
                    web:
                      exposure:
                        include: "*"
                """);
        // Профиль разработки: здесь пересоздание схемы и открытые пароли уместны
        Files.writeString(dir.resolve("application-dev.properties"), """
                spring.jpa.hibernate.ddl-auto=create
                spring.datasource.password=dev
                """);
        Files.writeString(dir.resolve("other.properties"), "spring.jpa.hibernate.ddl-auto=create\n");

        configFiles = new FileSystemConfigLoader().load(dir);
    }

    @Test
    void loadsOnlySpringConfigFiles() {
        assertThat(configFiles).extracting(file -> file.path().getFileName().toString())
                .containsExactly("application-dev.properties", "application.properties", "application.yml");
        assertThat(configFiles.get(2).find("spring.jpa.hibernate.ddl-auto"))
                .hasValueSatisfying(property -> {
                    assertThat(property.value()).isEqualTo("create");
                    assertThat(property.line()).isEqualTo(5);
                });
    }

    @Test
    void ddlAuto() {
        assertThat(check(new CheckDdlAutoRule())).containsExactly("application.properties:3", "application.yml:5");
    }

    @Test
    void openInView() {
        // В .properties свойство не задано (по умолчанию включено), в .yml включено явно
        assertThat(check(new CheckOpenInViewRule())).containsExactly("application.properties:1", "application.yml:3");
    }

    @Test
    void actuatorExposure() {
        assertThat(check(new CheckActuatorExposureRule()))
                .containsExactly("application.properties:4", "application.yml:12");
    }

    @Test
    void stacktraceExposure() {
        assertThat(check(new CheckStacktraceExposureRule())).containsExactly("application.properties:5");
    }

    @Test
    void secretInConfig() {
        assertThat(check(new CheckSecretInConfigRule())).containsExactly("application.properties:2", "application.yml:7");
    }

    private List<String> check(ConfigRule rule) {
        return configFiles.stream()
                .flatMap(file -> rule.checkConfig(file).stream())
                .map(violation -> violation.file().getFileName() + ":" + violation.line())
                .toList();
    }
}
