package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.SecretComparisonRule;
import ru.akvine.zond.rules.security.SecretInToStringRule;
import ru.akvine.zond.rules.security.SensitiveDataLoggingRule;
import ru.akvine.zond.rules.security.TrustAllSslRule;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила безопасности судят не по одному имени или вызову, а по тому, что на деле происходит:
 * раскрывается ли секрет, защищено ли значение, выполняется ли ветка с отключенной проверкой SSL
 */
class SecurityContextTest {
    private static final String SSL_CLIENT = """
            class Client {
                private boolean verifyHostname;
                @Value("${ssl.trust-all:false}")
                private boolean trustAll;
                private boolean insecure = true;
                void build(Builder builder, int mode, boolean flag) {
                    HostnameVerifier verifier = verifyHostname ? null : NoopHostnameVerifier.INSTANCE;
                    if (trustAll) {
                        builder.loadTrustMaterial((chain, authType) -> true);
                    }
                    if (insecure) {
                        builder.setHostnameVerifier((host, session) -> true);
                    }
                    if (mode == 2 && flag) {
                        builder.setHostnameVerifier((host, session) -> true);
                    }
                }
                void always(Builder builder) {
                    builder.setHostnameVerifier((host, session) -> true);
                }
            }
            """;

    @Test
    void sensitiveDataLoggingLooksAtWhatIsWritten() {
        assertThat(RuleTests.lines(new SensitiveDataLoggingRule(), """
                class Sample {
                    void run(String password, User user, Account account, String token) {
                        log.info("pwd {}", password);
                        log.info("length {}", password.length());
                        log.info("present {}", password != null);
                        log.info("masked {}", mask(token));
                        log.info("encrypted {}", user.getEncryptedPassword());
                        log.info("hash {}", account.getPasswordHash());
                        String secret = encryptor.encrypt(token);
                        log.info("secret {}", secret);
                        log.info("user {}", credentials.getUsername());
                        log.info("token " + token);
                        log.info("base64 {}", Base64.getEncoder().encodeToString(token.getBytes()));
                        log.info("hashed {}", passwordEncoder.encode(password));
                    }
                }
                """)).containsExactly(3, 12, 13);
    }

    @Test
    void propertyThatAlwaysHoldsHashIsNotSecret() {
        Map<String, String> files = Map.of(
                "UserService.java", """
                        class UserService {
                            void register(UserEntity entity, String raw) {
                                entity.setPassword(passwordEncoder.encode(raw));
                            }
                            void show(UserEntity entity, LoginRequest request) {
                                log.info("stored {}", entity.getPassword());
                                log.info("given {}", request.getPassword());
                            }
                        }
                        """,
                "UserEntity.java", """
                        @Data
                        class UserEntity {
                            private String password;
                        }
                        """,
                "LoginRequest.java", """
                        @Data
                        class LoginRequest {
                            private String password;
                        }
                        """);

        // В поле сущности кладут только результат encode(...): в логе и в toString() окажется хеш
        assertThat(places(RuleTests.checkProject(new SensitiveDataLoggingRule(), files)))
                .containsExactly("UserService.java:7");
        assertThat(places(RuleTests.checkProject(new SecretInToStringRule(), files)))
                .containsExactly("LoginRequest.java:3");
    }

    @Test
    void secretComparisonIgnoresLabelsAndProtectedValues() {
        assertThat(RuleTests.lines(new SecretComparisonRule(), """
                class Sample {
                    private static final String SECRET = "Secret";
                    private static final String API_TOKEN = "f3a9c1e07b";
                    boolean run(String kind, String secret, String input, User user, String botPathWithSecret) {
                        boolean a = SECRET.equals(kind);
                        boolean b = secret.equals(input);
                        boolean c = user.getPasswordHash().equals(input);
                        boolean d = botPathWithSecret.equals(input);
                        boolean e = API_TOKEN.equals(input);
                        return a && b && c && d && e;
                    }
                }
                """)).containsExactly(6, 9);
    }

    @Test
    void sslDisabledUnderConditionDependsOnFlagValue() {
        // Настроек нет: verifyHostname и trustAll ветку с отключением не включают, insecure = true - включает;
        // условие из двух частей не разбирается
        assertThat(lines(List.of())).containsExactly(12, 19);

        assertThat(lines(List.of(config("application.properties", "http.client.verify-hostname", "false"))))
                .containsExactly(7, 12, 19);
        assertThat(lines(List.of(config("application.properties", "http.client.verify-hostname", "true"))))
                .containsExactly(12, 19);
        assertThat(lines(List.of(config("application.yml", "ssl.trust-all", "true")))).containsExactly(9, 12, 19);

        // Профиль разработки в рабочую среду не попадает; флаг, выключенный в настройках, находкой не является
        assertThat(lines(List.of(
                config("application-dev.properties", "http.client.verify-hostname", "false"),
                config("application.properties", "app.insecure", "false")))).containsExactly(19);
    }

    @Test
    void sslMessageNamesTheSetting() {
        ScanContext context = context(List.of(config("application.properties", "http.client.verify-hostname", "false")));

        assertThat(new TrustAllSslRule().checkContext(context)).extracting(Violation::message)
                .anyMatch(message -> message.contains("'http.client.verify-hostname=false' (application.properties:1)"))
                .anyMatch(message -> message.contains("флаг 'insecure' по умолчанию равен true"));
    }

    private List<Integer> lines(List<ConfigFile> configFiles) {
        return new TrustAllSslRule().checkContext(context(configFiles)).stream().map(Violation::line).toList();
    }

    private ScanContext context(List<ConfigFile> configFiles) {
        SourceFile source = RuleTests.parse(Path.of("Client.java"), SSL_CLIENT);
        return new ScanContext(Path.of("."), List.of(source), configFiles, List.of());
    }

    private ConfigFile config(String name, String key, String value) {
        return new ConfigFile(Path.of(name), List.of(new ConfigProperty(key, value, 1)));
    }

    private List<String> places(List<Violation> violations) {
        return violations.stream().map(violation -> violation.file().getFileName() + ":" + violation.line()).sorted().toList();
    }
}
