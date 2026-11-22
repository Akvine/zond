package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.CardDataInClearRule;
import ru.akvine.zond.rules.security.PrivateKeyInProjectRule;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:562, jr:565: то, что лежит на диске открытым, - номер карты в хранимом объекте и закрытый ключ в папке проекта
 */
class SensitiveDataAtRestRulesTest {

    @Test
    void fullCardNumberAndVerificationCodeInEntityAreFound() {
        List<Violation> violations = RuleTests.check(new CardDataInClearRule(), """
                @Entity
                @Table(name = "CARD")
                @Data
                public class CardEntity {
                    @Id
                    private Long id;
                    @Column(name = "MASKED_PAN")
                    private String maskedPan;
                    @Column(name = "FULL_PAN")
                    private String fullPan;
                    @Column(name = "EXPIRATION")
                    private String expiration;
                    private String cvv;
                }
                """);
        assertThat(violations).extracting(Violation::line).containsExactly(10, 13);
        assertThat(violations.get(0).message()).contains("'fullPan'", "PCI DSS", "@Convert", "toString()");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(violations.get(1).message()).contains("'cvv'", "запрещает хранить");
    }

    @Test
    void cardNumberOfUnknownFormIsOnlySuspicion() {
        // Поля с маской рядом нет, слова full в имени нет: в поле может лежать и маска, и токен
        List<Violation> violations = RuleTests.check(new CardDataInClearRule(), """
                @Entity
                public class PaymentEntity {
                    @Id
                    private Long id;
                    private String cardNumber;
                }
                """);
        assertThat(violations).extracting(Violation::line).containsExactly(5);
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
        assertThat(violations.get(0).message()).doesNotContain("toString()");
    }

    @Test
    void protectedOrNotStoredCardDataIsFine() {
        assertThat(RuleTests.check(new CardDataInClearRule(), """
                @Entity
                public class CardEntity {
                    @Id
                    private Long id;
                    private String maskedPan;
                    @Convert(converter = CardNumberEncryptor.class)
                    private String fullPan;
                    @ColumnTransformer(read = "decrypt(card_number)", write = "encrypt(?)")
                    private String cardNumber;
                    private String panHash;
                    private String cardToken;
                    private String company;
                    private int span;
                }
                // Не хранимый объект: запрос к платежному шлюзу
                @Data
                class PayRequest {
                    private String pan;
                    private String cvv;
                }
                """)).isEmpty();
    }

    private static byte[] jks(int entryKind) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0xFEEDFEED);
            output.writeInt(2);
            output.writeInt(1);
            output.writeInt(entryKind);
            output.writeUTF("entry");
            output.writeLong(0L);
            output.writeUTF("X.509");
            output.writeInt(4);
            output.write(new byte[]{1, 2, 3, 4});
        }
        return bytes.toByteArray();
    }

    // Начало PKCS #12 и идентификатор мешка: 0x02 - pkcs8ShroudedKeyBag (закрытый ключ), 0x03 - certBag
    private static byte[] pkcs12(int bagKind) {
        return new byte[]{0x30, (byte) 0x82, 0x01, 0x00, 0x06, 0x0B, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
                0x0D, 0x01, 0x0C, 0x0A, 0x01, (byte) bagKind, 0x00, 0x00};
    }

    private static void write(Path project, String path, byte[] content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    @Test
    void privateKeyInProjectFolderIsFound(@TempDir Path project) throws IOException {
        write(project, ".gitignore", "target/\nlocal/\n".getBytes());
        write(project, "app/src/main/resources/server.jks", jks(1));
        write(project, "app/src/main/resources/client.p12", pkcs12(2));
        write(project, "deploy/tls/service.key",
                "-----BEGIN PRIVATE KEY-----\nMIIEvQ\n-----END PRIVATE KEY-----\n".getBytes());
        // Только сертификаты: секрета в таких файлах нет
        write(project, "app/src/main/resources/truststore.jks", jks(2));
        write(project, "app/src/main/resources/truststore.p12", pkcs12(3));
        write(project, "deploy/tls/service.pem",
                "-----BEGIN CERTIFICATE-----\nMIIC\n-----END CERTIFICATE-----\n".getBytes());
        // Закрыт .gitignore
        write(project, "local/dev.jks", jks(1));

        List<Violation> violations = new ProjectFixture(project).check(new PrivateKeyInProjectRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName().toString())
                .containsExactly("client.p12", "server.jks", "service.key");
        assertThat(violations.get(1).message())
                .contains("'app/src/main/resources/server.jks'", "не закрыт .gitignore", "в каждую сборку")
                .contains("перевыпустите");
        assertThat(violations.get(2).message()).contains("Файл с закрытым ключом").doesNotContain("в каждую сборку");
        assertThat(violations).allMatch(violation -> violation.confidenceOrDefault() == Confidence.PROBABLE);
    }

    @Test
    void keyOfTestOrOutsideRepositoryIsOnlySuspicion(@TempDir Path projects) throws IOException {
        Path repository = projects.resolve("repository");
        write(repository, ".gitignore", "target/\n".getBytes());
        write(repository, "src/main/resources/localhost-selfsigned.jks", jks(1));
        assertThat(new ProjectFixture(repository).check(new PrivateKeyInProjectRule()))
                .extracting(Violation::confidenceOrDefault).containsExactly(Confidence.SUSPICION);
        // Ни .git, ни .gitignore: хранится ли папка в git, неизвестно
        Path plain = projects.resolve("plain");
        write(plain, "src/main/resources/server.jks", jks(1));
        assertThat(new ProjectFixture(plain).check(new PrivateKeyInProjectRule()))
                .extracting(Violation::confidenceOrDefault).containsExactly(Confidence.SUSPICION);
    }

    @Test
    void keyOfSamplesIsOnlySuspicion(@TempDir Path projects) throws IOException {
        // Хранилище с примерами: ключи в нем показательные
        Path samples = projects.resolve("payments-samples");
        write(samples, ".gitignore", "target/\n".getBytes());
        write(samples, "x509/src/main/resources/server.jks", jks(1));
        assertThat(new ProjectFixture(samples).check(new PrivateKeyInProjectRule()))
                .extracting(Violation::confidenceOrDefault).containsExactly(Confidence.SUSPICION);
        // Каталог примеров в обычном проекте; каталог contest примером не считается
        Path repository = projects.resolve("repository");
        write(repository, ".gitignore", "target/\n".getBytes());
        write(repository, "docs/examples/tls/server.jks", jks(1));
        write(repository, "contest/server.jks", jks(1));
        assertThat(new ProjectFixture(repository).check(new PrivateKeyInProjectRule()))
                .extracting(violation -> violation.file().getParent().getFileName() + " "
                        + violation.confidenceOrDefault())
                .containsExactly("contest " + Confidence.PROBABLE, "tls " + Confidence.SUSPICION);
    }
}
