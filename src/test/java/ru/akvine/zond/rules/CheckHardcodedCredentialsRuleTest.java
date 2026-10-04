package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.CheckHardcodedCredentialsRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckHardcodedCredentialsRuleTest {
    private final CheckHardcodedCredentialsRule rule = new CheckHardcodedCredentialsRule();

    @Test
    void findsSecretsWrittenAsLiterals() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    private static final String PASSWORD = "qwerty123";
                    private String apiKey = "sk-live-0123456789";
                    void connect() {
                        String dbPassword = "s3cr3t";
                        this.token = "abcdef";
                        config.setPassword("hunter2");
                    }
                    private static final String PASSWORD_KEY = "password";
                    private static final String TOKEN_HEADER = "X-Auth-Token";
                    @Value("${db.password}")
                    private String password;
                    private String secret = "${app.secret}";
                    private String emptyPassword = "";
                    private String userName = "admin";
                    void ok() {
                        config.setPassword(password);
                        String passwordFromEnv = System.getenv("DB_PASSWORD");
                    }
                    private static final String INVALID_TOKEN_CODE = "E42";
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(2, 3, 5, 6, 7);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:19"));
        assertThat(violations.get(0).message()).contains("'PASSWORD'").doesNotContain("qwerty123");
    }

    @Test
    void wordAboutSecretInNameIsNotSecret() {
        assertThat(RuleTests.lines(rule, """
                class Texts {
                    static final String REVOKE_TOKEN_BUTTON_TEXT = "Revoke";
                    static final String TOKEN_TITLE = "Tokens";
                    static final String INVALID_PASSWORD = "E401";
                    static final String RESET_PASSWORD = "reset";
                    static final String passwordHint = "qwerty";
                    static final String tokenServiceBean = "jwtTokens";
                    static final String ADMIN_TOKEN = "f3a9c1d07b";
                    static final String passwordForAdmin = "hunter2";
                    static final String TOKEN_VALUE = "f3a9c1d07b";
                    static final String dbpassword = "s3cr3t";
                    static final String password2 = "s3cr3t";
                }
                """)).containsExactly(8, 9, 10, 11, 12);
    }

    @Test
    void textAddressAndKeyNameAreNotSecretValues() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    String token = "Отозвать токен";
                    String password = "Enter your password";
                    String secret = "jwt.secret";
                    String apiKey = "x-api-key";
                    String accessToken = "/oauth/token";
                    String credential = "https://example.org/login";
                    String dbPassword = "postgres://admin:hunter2@db/app";
                    String authToken = "Bearer eyJhbGciOiJIUzI1NiJ9";
                    String pwd = "P@ssw0rd!";
                }
                """)).containsExactly(8, 9, 10);
    }

    @Test
    void ignoresTestClasses() {
        assertThat(RuleTests.lines(rule, """
                class LoginTest {
                    private String password = "qwerty123";

                    @Test
                    void logsIn() {}
                }
                """)).isEmpty();
    }
}
