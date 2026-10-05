package ru.akvine.zond.rules.support;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Типы не помогают понять, что значение секретное, - об этом судим по имени переменной, поля или свойства
 * и по виду самого значения.
 * <p>
 * Имя разбирается по словам. Секретом считается то, что названо секретом: dbPassword, API_KEY, adminToken.
 * Если слово "token" стоит в середине имени (REVOKE_TOKEN_BUTTON_TEXT, tokenService, password.min-length),
 * имя описывает что-то, относящееся к секрету, а не сам секрет.
 */
@UtilityClass
public class Secrets {
    // Граница слов: смена регистра в camelCase, а также _ . - и пробел
    private final static Pattern WORD_BOUNDARY =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_.\\-\\s]+");

    private final static List<String> SECRET_WORDS = List.of(
            "password", "passwd", "pwd", "passphrase", "secret", "token", "credential", "cvv", "apikey");

    // Секрет из двух слов: api key, card number
    private final static Set<String> SECRET_PAIRS = Set.of(
            "api key", "private key", "access key", "secret key", "card number", "pin code");

    // Слова после секрета, которые ничего не меняют: passwordValue, TOKEN_DEFAULT, secretBase64
    private final static Set<String> NEUTRAL_SUFFIXES =
            Set.of("value", "val", "string", "str", "default", "plain", "raw", "base64", "hex", "bytes");

    // passwordForAdmin, tokenOfService: секрет - то, что до предлога
    // botPathWithSecret - это путь, а не секрет: слово после with лишь уточняет
    private final static Set<String> PREPOSITIONS = Set.of("for", "of", "from", "to", "in", "with", "by");

    // encryptedPassword, passwordHash, maskedToken: значение уже зашифровано, захешировано или скрыто.
    // В логе или в toString() оно секрет не раскрывает
    private final static Set<String> PROTECTED_WORDS = Set.of(
            "encrypted", "encoded", "hashed", "hash", "masked", "mask", "digest", "cipher", "ciphered",
            "bcrypt", "salted", "obfuscated", "checksum", "fingerprint", "signature", "enc");

    // INVALID_TOKEN, RESET_PASSWORD, showPassword: действие с секретом, его состояние или текст ошибки
    private final static Set<String> ABOUT_PREFIXES = Set.of(
            "invalid", "expired", "missing", "wrong", "bad", "empty", "no", "unknown", "revoked", "revoke", "reset",
            "change", "changed", "forgot", "forget", "validate", "verify", "check", "generate", "create", "delete",
            "remove", "show", "hide", "enter", "confirm", "send", "need", "require", "required");

    // "Bearer eyJhbGciOi...": пробел есть, но это значение, а не фраза
    private final static Pattern AUTHORIZATION_VALUE = Pattern.compile("^(Bearer|Basic|Token)\\s+\\S+$");

    // access_token, x-auth-token, jwt.secret: название параметра, заголовка или свойства
    private final static Pattern KEY_LIKE_VALUE = Pattern.compile("^[a-z]+([._\\-][a-z]+)+$");

    // /api/token, https://example.org/oauth/token; адрес с логином и паролем внутри - отдельный случай
    private final static Pattern PATH_LIKE_VALUE = Pattern.compile("^(/|[a-z][a-z0-9+.\\-]*://)[^@]*$");

    // ${db.password}, #{...}: значение подставляется из настроек
    private final static Pattern PLACEHOLDER_VALUE = Pattern.compile("^[$#]\\{.*");

    private final static Pattern WHITESPACE = Pattern.compile("\\s");

    // Заглушки: их ставят в примерах и шаблонах настроек вместо настоящего значения.
    // Слабые, но настоящие пароли (password, admin, 123456) сюда не входят - о них сообщать нужно
    private final static Pattern STUB_VALUE = Pattern.compile(
            "change[_-]?(me|it)|replace[_-]?(me|this)|to[_-]?do|placeholder|dummy|sample|example|undefined|n/a"
                    + "|x{3,}|\\*{3,}|\\.{3,}|<[^>]*>|\\[[^\\]]*]|your[_-].*|.*[_-]here|.*_placeholder",
            Pattern.CASE_INSENSITIVE);
    private final static Pattern NON_ASCII_LETTER = Pattern.compile("[^\\p{ASCII}]");
    private final static Pattern TRAILING_DIGITS = Pattern.compile("\\d+$");

    /**
     * @return true, если имя называет сам секрет, а не что-то, что к нему относится
     */
    public boolean isSecretName(String name) {
        List<String> words = meaningfulWords(name);
        if (words.isEmpty() || ABOUT_PREFIXES.contains(words.get(0)) || isProtectedName(name)) {
            return false;
        }

        String last = words.get(words.size() - 1);
        if (words.size() > 1 && SECRET_PAIRS.contains(words.get(words.size() - 2) + " " + singular(last))) {
            return true;
        }
        // Окончание, а не точное совпадение: имя могло быть записано слитно - dbpassword, apitoken
        String word = singular(last);
        return SECRET_WORDS.stream().anyMatch(word::endsWith);
    }

    /**
     * @return true, если строка может быть секретом: не пустая, не подстановка из настроек и не похожа
     * на подпись, сообщение, адрес или название параметра
     */
    public boolean isSecretValue(String text) {
        if (text.isBlank() || PLACEHOLDER_VALUE.matcher(text).matches() || isStub(text)) {
            return false;
        }
        if (AUTHORIZATION_VALUE.matcher(text).matches()) {
            return true;
        }
        // "Secret", "password", "TOKEN": название вида значения, а не само значение
        if (SECRET_WORDS.contains(singular(text.toLowerCase(Locale.ROOT)))) {
            return false;
        }
        // Фраза из нескольких слов или текст не на латинице - подпись кнопки, сообщение, описание
        if (WHITESPACE.matcher(text).find() || NON_ASCII_LETTER.matcher(text).find()) {
            return false;
        }
        return !KEY_LIKE_VALUE.matcher(text).matches() && !PATH_LIKE_VALUE.matcher(text).matches();
    }

    /**
     * @return true для значения-заглушки, которое ставят вместо настоящего секрета: changeme, <your-token>, xxx
     */
    public boolean isStub(String text) {
        return STUB_VALUE.matcher(text.trim()).matches();
    }

    /**
     * @return true, если имя говорит, что значение защищено: encryptedPassword, tokenHash
     */
    public boolean isProtectedName(String name) {
        for (String word : WORD_BOUNDARY.split(name)) {
            if (PROTECTED_WORDS.contains(TRAILING_DIGITS.matcher(word).replaceAll("").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // Слова имени в нижнем регистре без нейтральных окончаний и без того, что стоит после предлога
    private List<String> meaningfulWords(String name) {
        List<String> words = new ArrayList<>();
        for (String word : WORD_BOUNDARY.split(name)) {
            // password2 -> password: номер смысла слова не меняет
            String letters = TRAILING_DIGITS.matcher(word).replaceAll("");
            if (!letters.isEmpty()) {
                words.add(letters.toLowerCase(Locale.ROOT));
            }
        }

        for (int index = 1; index < words.size(); index++) {
            if (PREPOSITIONS.contains(words.get(index))) {
                words = new ArrayList<>(words.subList(0, index));
                break;
            }
        }
        while (words.size() > 1 && isNeutral(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        return words;
    }

    private boolean isNeutral(String word) {
        return NEUTRAL_SUFFIXES.contains(word);
    }

    // passwords -> password, credentials -> credential
    private String singular(String word) {
        return word.endsWith("s") && word.length() > 1 ? word.substring(0, word.length() - 1) : word;
    }
}
