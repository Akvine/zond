package ru.akvine.zond.rules;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.akvine.zond.rules.support.Secrets;

import static org.assertj.core.api.Assertions.assertThat;

class SecretsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "password", "PASSWORD", "dbPassword", "DB_PASSWORD", "adminToken", "apiKey", "API_KEY", "apikey",
            "secretKey", "clientSecret", "privateKey", "cardNumber", "pinCode", "userPasswords", "credentials",
            "spring.datasource.password", "jwt.secret-key", "passwordValue", "TOKEN_DEFAULT", "passwordForAdmin",
            "refreshToken", "dbpassword", "password2"})
    void namesOfSecrets(String name) {
        assertThat(Secrets.isSecretName(name)).as(name).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "REVOKE_TOKEN_BUTTON_TEXT", "TOKEN_HEADER", "tokenService", "passwordEncoder", "password.min-length",
            "token-uri", "INVALID_TOKEN", "RESET_PASSWORD", "showPassword", "tokenExpired", "passwordHint",
            "TOKEN_TITLE", "secretQuestionLabel", "userName", "key", "number", "tokenizer", "broken"})
    void namesAboutSecrets(String name) {
        assertThat(Secrets.isSecretName(name)).as(name).isFalse();
    }
}
