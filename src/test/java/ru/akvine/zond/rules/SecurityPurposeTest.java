package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.security.CorsAllowAllRule;
import ru.akvine.zond.rules.security.DisabledSecurityRule;
import ru.akvine.zond.rules.security.HardcodedCredentialsRule;
import ru.akvine.zond.rules.security.SecretInConfigRule;
import ru.akvine.zond.rules.security.WeakHashRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила безопасности учитывают, для чего используется опасная вещь и в каком окружении она стоит
 */
class SecurityPurposeTest {

    @Test
    void weakHashDependsOnWhatIsHashed() {
        List<Violation> violations = RuleTests.check(new WeakHashRule(), """
                class Sample {
                    String passwordHash(String password) {
                        return DigestUtils.md5Hex(password);
                    }
                    String checksum(byte[] content) {
                        return DigestUtils.md5Hex(content);
                    }
                    String cacheKey(String query) {
                        return Hashing.sha1().hashString(query).toString();
                    }
                    String unknown(String value) {
                        return DigestUtils.md5Hex(value);
                    }
                    byte[] sign(byte[] data, String apiKey) throws Exception {
                        return MessageDigest.getInstance("SHA-1").digest(data);
                    }
                }
                """);

        // Контрольная сумма и ключ кэша - не защита: находки нет. Пароль и подпись - вероятная ошибка.
        // Когда назначение не видно, остается подозрение
        assertThat(violations).extracting(Violation::line).containsExactly(3, 12, 15);
        assertThat(violations).extracting(Violation::confidence)
                .containsExactly(Confidence.PROBABLE, Confidence.SUSPICION, Confidence.PROBABLE);
    }

    @Test
    void csrfMayBeDisabledForStatelessApi() {
        String sessions = """
                class Config {
                    void configure(HttpSecurity http) throws Exception {
                        http.csrf(csrf -> csrf.disable());
                        http.formLogin(Customizer.withDefaults());
                    }
                }
                """;
        String stateless = """
                class Config {
                    void configure(HttpSecurity http) throws Exception {
                        http.csrf(csrf -> csrf.disable());
                        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
                        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
                    }
                }
                """;

        assertThat(RuleTests.lines(new DisabledSecurityRule(), sessions)).containsExactly(3);
        // Сессий нет - подделывать нечего; открытый для всех доступ остается находкой
        assertThat(RuleTests.lines(new DisabledSecurityRule(), stateless)).containsExactly(5);
    }

    @Test
    void openCorsIsCertainOnlyWithCredentials() {
        List<Violation> withCredentials = RuleTests.check(new CorsAllowAllRule(), """
                class Config {
                    void configure(CorsRegistry registry) {
                        registry.addMapping("/**").allowedOriginPatterns("*").allowCredentials(true);
                    }
                }
                """);
        List<Violation> publicApi = RuleTests.check(new CorsAllowAllRule(), """
                class Config {
                    void configure(CorsRegistry registry) {
                        registry.addMapping("/public/**").allowedOrigins("*");
                    }
                }
                """);

        assertThat(withCredentials).extracting(Violation::confidence).containsOnly(Confidence.CONFIRMED);
        assertThat(publicApi).extracting(Violation::confidence).containsOnly(Confidence.SUSPICION);
    }

    @Test
    void stubValuesAreNotSecrets() {
        assertThat(RuleTests.lines(new HardcodedCredentialsRule(), """
                class Sample {
                    private String password = "changeme";
                    private String apiToken = "<your-token>";
                    private String secret = "xxxxxxxx";
                    private String dbPassword = "qwerty123";
                    private String adminPassword = "root123";
                }
                """)).containsExactly(5, 6);

        ConfigFile config = new ConfigFile(Path.of("application.properties"), List.of(
                new ConfigProperty("app.api.token", "your-token-here", 1),
                new ConfigProperty("app.mail.password", "REPLACE_ME", 2),
                new ConfigProperty("spring.datasource.password", "s3cr3t", 3)));
        assertThat(new SecretInConfigRule().checkConfig(config)).extracting(Violation::line).containsExactly(3);
    }

    @Test
    void chainFoundByNamesIsOnlyProbable() {
        // Без разрешения типов вызов enrich(...) сопоставлен с методом по имени: путь вероятен, но не установлен
        List<Violation> violations = RuleTests.check(new RepositoryCallInLoopRule(), """
                class Orders {
                    private OrderRepository orderRepository;
                    void process(List<Long> ids) {
                        for (Long id : ids) {
                            enrich(id);
                        }
                    }
                    void enrich(Long id) {
                        orderRepository.findById(id);
                    }
                }
                """);

        assertThat(violations).singleElement()
                .satisfies(violation -> assertThat(violation.confidence()).isEqualTo(Confidence.PROBABLE));
    }
}
