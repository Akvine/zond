package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.KafkaSaslOverPlaintextRule;
import ru.akvine.zond.rules.security.TlsCheckDisabledInConfigRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:558, jr:559: защита соединения, отключенная настройкой, - проверка сертификата и имени хоста, шифрование
 * подключения к Kafka
 */
class TransportSettingsRulesTest {

    private static List<Violation> check(ConfigRule rule, ProjectFixture project) {
        return project.context().configFiles().stream()
                .flatMap(file -> rule.checkConfig(file).stream())
                .sorted((first, second) -> first.file().equals(second.file())
                        ? Integer.compare(first.line(), second.line())
                        : first.file().compareTo(second.file()))
                .toList();
    }

    @Test
    void settingThatSwitchesCertificateCheckOffIsFound(@TempDir Path project) {
        List<Violation> violations = check(new TlsCheckDisabledInConfigRule(), new ProjectFixture(project)
                .write("src/main/resources/application.properties", """
                        billing.client.trust-all=true
                        billing.client.url=https://billing.example
                        gateway.integration.verify.hostname=false
                        partner.ssl.verify-hostname=true
                        spring.mail.properties.mail.smtp.ssl.trust=*
                        spring.kafka.properties.ssl.endpoint.identification.algorithm=
                        spring.rabbitmq.ssl.validate-server-certificate=false
                        feature.trust-allowed-partners=true
                        report.verify-totals=false
                        """));
        assertThat(violations).extracting(Violation::line).containsExactly(1, 3, 5, 6, 7);
        assertThat(violations.get(0).message()).contains("'billing.client.trust-all=true'", "любой сертификат");
        assertThat(violations.get(1).message()).contains("имени хоста", "любое другое имя");
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.MAJOR);
    }

    @Test
    void checkSwitchedOffOnlyForDeveloperIsNotCertain(@TempDir Path project) {
        ProjectFixture fixture = new ProjectFixture(project)
                .write(".gitignore", "/src/main/resources/application-*.properties\n")
                .write("src/main/resources/application-dev.yml", "billing:\n  client:\n    trust-all: true\n")
                .write("src/main/resources/application-BANK.properties", "billing.client.trust-all=true\n")
                .write("src/main/resources/bootstrap.yml", "config:\n  server:\n    verify-hostname: false\n");
        List<Violation> violations = check(new TlsCheckDisabledInConfigRule(), fixture);
        // Профиль разработки не проверяется вовсе; файл, закрытый .gitignore, - только подозрение
        assertThat(violations).extracting(violation -> violation.file().getFileName().toString())
                .containsExactly("application-BANK.properties", "bootstrap.yml");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
        assertThat(violations.get(1).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
    }

    @Test
    void checkOfConnectionWithoutTlsIsNotReported(@TempDir Path project) {
        // У клиента cache шифрование выключено целиком: сверять нечего. У клиента search оно включено
        List<Violation> violations = check(new TlsCheckDisabledInConfigRule(), new ProjectFixture(project)
                .write("src/main/resources/application.properties", """
                        cache.redis.ssl=false
                        cache.redis.verifyPeer=false
                        search.client.ssl.enabled=true
                        search.client.ssl.verify-hostname=false
                        queue.broker.ssl.enabled=false
                        queue.broker.ssl.verify-hostname=false
                        """));
        assertThat(violations).extracting(Violation::line).containsExactly(4);
    }

    @Test
    void kafkaWithPasswordButWithoutEncryptionIsFound(@TempDir Path project) {
        List<Violation> violations = check(new KafkaSaslOverPlaintextRule(), new ProjectFixture(project)
                .write("src/main/resources/application.properties", """
                        spring.kafka.properties.security.protocol=SASL_PLAINTEXT
                        spring.kafka.properties.sasl.mechanism=SCRAM-SHA-512
                        audit.kafka.security-protocol=SASL_PLAINTEXT
                        audit.kafka.sasl-mechanism=PLAIN
                        reports.kafka.security.protocol=SASL_SSL
                        local.kafka.security.protocol=PLAINTEXT
                        """));
        assertThat(violations).extracting(Violation::line).containsExactly(1, 3);
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(0).message())
                .contains("SCRAM-SHA-512", "сообщения идут открытым текстом", "SASL_SSL");
        assertThat(violations.get(1).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(violations.get(1).message()).contains("PLAIN", "пароль по сети открытым текстом");
    }
}
