package ru.akvine.zond.rules.support;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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

    // Слова, которыми кончается имя не самого секрета, а чего-то о нем: кода ошибки, правила, события, заголовка
    private final static Set<String> DESCRIPTOR_WORDS = Set.of(
            "code", "rule", "error", "message", "exception", "event", "name", "header", "param", "parameter", "field",
            "property", "prefix", "suffix", "pattern", "format", "type", "kind", "label", "title", "description",
            "handler", "validator", "filter", "length", "size", "count", "ttl", "timeout", "expiration", "mode", "flag",
            "enabled", "test", "check");

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

    // ACTION_SET_CREDENTIALS, PARAM_PASSWORD, HEADER_TOKEN, CLAIM_SECRET: первое слово говорит, что константа
    // хранит название - действия, параметра, заголовка, - а не сам секрет
    private final static Set<String> NAMING_PREFIXES = Set.of(
            "action", "view", "param", "parameter", "header", "field", "attr", "attribute", "property", "prop",
            "column", "claim", "env", "arg", "argument", "option", "cookie", "label", "msg", "message", "error", "err",
            "title", "text", "event", "topic", "queue", "path", "url", "uri", "endpoint", "route", "scope", "grant",
            "role", "permission", "authority", "type", "kind", "mode", "format", "pattern", "prefix", "suffix");

    // DEFAULT_PASSWORD = "false": значение настройки по умолчанию, а не пароль
    private final static Set<String> NOT_VALUES = Set.of(
            "true", "false", "null", "none", "nil", "yes", "no", "on", "off", "default", "empty", "unknown",
            "undefined");
    // {noop}secret, {bcrypt}$2a$10$...: имя кодировщика паролей Spring Security перед значением
    private final static Pattern ENCODED_VALUE = Pattern.compile("^\\{([\\w-]+)}(.*)$");
    private final static String PLAIN_ENCODER = "noop";
    // %TOKEN%, {token}, <token>, $token, :token, %s: место для значения в шаблоне, а не значение
    private final static Pattern TEMPLATE_VALUE = Pattern.compile("^(%\\w+%?|\\{\\w*}|<\\w+>|\\$\\w+|:\\w+)$");
    // ApolloUserToken, X-Auth-Token, setSessionTokenValue: слова без цифр и знаков, одно из них - о секрете
    private final static Pattern WORDS_ONLY = Pattern.compile("^[A-Za-z]+([-_.][A-Za-z]+)*$");
    private final static Set<String> SECRET_NAME_WORDS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "credential", "key", "apikey", "auth", "authorization",
            "authentication");
    // Значения, с которыми идут готовые образы баз и брокеров и которые пишут в примерах: настоящий секрет
    // так не выглядит, а для рабочей среды такое значение почти наверняка переопределяют
    private final static Set<String> WELL_KNOWN_DEFAULTS = Set.of(
            "password", "pass", "passwd", "pwd", "guest", "admin", "administrator", "root", "test", "testing", "secret",
            "postgres", "postgresql", "mysql", "mariadb", "oracle", "sa", "user", "username", "demo", "changeit",
            "123", "1234", "12345", "123456", "1234567", "12345678", "123456789", "1234567890", "qwerty", "qwerty123",
            "abc123", "password1", "password123", "p@ssw0rd", "letmein", "welcome", "minioadmin", "elastic", "redis",
            "rabbitmq", "kafka", "mongo", "mongodb", "local", "localhost", "dev", "development", "foo", "bar", "foobar",
            "token", "key", "apikey", "api-key", "mysecret", "my-secret", "secretkey", "secret-key", "s3cr3t",
            "default_token", "default-token", "defaulttoken", "access_token", "test-token", "testtoken");

    // "Bearer eyJhbGciOi...": пробел есть, но это значение, а не фраза
    private final static Pattern AUTHORIZATION_VALUE = Pattern.compile("^(Bearer|Basic|Token)\\s+\\S+$");

    // access_token, x-auth-token, jwt.secret: название параметра, заголовка или свойства
    private final static Pattern KEY_LIKE_VALUE = Pattern.compile("^[a-z]+([._\\-][a-z]+)+$");
    // LOGIN_QRCODE_TOKEN = "LQT:", "user:session:": префикс ключа в Redis, к которому дописывают идентификатор
    private final static Pattern CACHE_KEY_PREFIX = Pattern.compile("^([A-Za-z][\\w.\\-]*:)+$");

    // /api/token, https://example.org/oauth/token; адрес с логином и паролем внутри - отдельный случай
    private final static Pattern PATH_LIKE_VALUE = Pattern.compile("^(/|[a-z][a-z0-9+.\\-]*://)[^@]*$");

    // ${db.password}, #{...}: значение подставляется из настроек
    // ${ENV_VAR}, #{выражение}, {{ .Values.secret }} из шаблона Helm, @token@ из сборки Maven:
    // значение подставят при развертывании, в файле секрета нет
    private final static Pattern PLACEHOLDER_VALUE = Pattern.compile("^[$#]\\{.*|^\\{\\{.*}}$|^@[\\w.\\-]+@$");

    private final static Pattern WHITESPACE = Pattern.compile("\\s");

    // Заглушки: их ставят в примерах и шаблонах настроек вместо настоящего значения.
    // Слабые, но настоящие пароли (password, admin, 123456) сюда не входят - о них сообщать нужно
    private final static Pattern STUB_VALUE = Pattern.compile(
            "change[_-]?(me|it)|replace[_-]?(me|this)|to[_-]?do|placeholder|dummy|sample|example|undefined|n/a"
                    + "|x{3,}|\\*{3,}|\\.{3,}|<[^>]*>|\\[[^\\]]*]|your[_-].*|.*[_-]here|.*_placeholder"
                    // sk-??, sk-****, AKIA????: шаблон, в котором вместо значения стоят вопросы или звездочки
                    + "|.*(\\?{2,}|\\*{3,}).*",
            Pattern.CASE_INSENSITIVE);
    private final static Pattern NON_ASCII_LETTER = Pattern.compile("[^\\p{ASCII}]");
    private final static Pattern TRAILING_DIGITS = Pattern.compile("\\d+$");

    /**
     * @return true, если имя называет сам секрет, а не что-то, что к нему относится
     */
    public boolean isSecretName(String name) {
        List<String> words = meaningfulWords(name);
        if (words.isEmpty() || ABOUT_PREFIXES.contains(words.get(0)) || isProtectedName(name)
                || words.size() > 1 && NAMING_PREFIXES.contains(words.get(0))) {
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
        // TOKEN = ".", SEPARATOR = "::" - разделитель: в секрете есть буквы или цифры
        if (text.chars().noneMatch(Character::isLetterOrDigit)) {
            return false;
        }
        if (NOT_VALUES.contains(text.toLowerCase(Locale.ROOT)) || TEMPLATE_VALUE.matcher(text).matches()) {
            return false;
        }
        // {noop} без пароля - пароля нет; {bcrypt}$2a$... - уже хеш, а не сам пароль
        Matcher encoded = ENCODED_VALUE.matcher(text);
        if (encoded.matches() && (encoded.group(2).isBlank() || !PLAIN_ENCODER.equals(encoded.group(1)))) {
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
        return !KEY_LIKE_VALUE.matcher(text).matches() && !PATH_LIKE_VALUE.matcher(text).matches()
                && !CACHE_KEY_PREFIX.matcher(text).matches();
    }

    /**
     * @return true, если значение похоже на название: оно состоит из одних слов, и одно из них - о секрете
     * (ApolloUserToken, X-Auth-Token). Так называют заголовок, куки или атрибут сессии; секрет таким бывает редко
     */
    public boolean isNameLike(String text) {
        if (!WORDS_ONLY.matcher(text).matches()) {
            return false;
        }
        String[] words = WORD_BOUNDARY.split(text);
        if (words.length < 2) {
            return false;
        }
        for (String word : words) {
            if (SECRET_NAME_WORDS.contains(singular(word.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true для общеизвестного значения по умолчанию: password, guest, postgres, 123456, changeit
     */
    public boolean isWellKnownDefault(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        Matcher encoded = ENCODED_VALUE.matcher(value);
        return WELL_KNOWN_DEFAULTS.contains(encoded.matches() ? encoded.group(2) : value);
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

        // SECRET_IN_CONFIG_RULE_CODE - это код правила о секретах, а не секрет: имя называет то, чем оно
        // заканчивается. Поэтому хвост после предлога отбрасывается, только если он не кончается таким словом
        boolean describesSomethingElse = DESCRIPTOR_WORDS.contains(words.isEmpty() ? "" : words.get(words.size() - 1));
        for (int index = 1; index < words.size() && !describesSomethingElse; index++) {
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
