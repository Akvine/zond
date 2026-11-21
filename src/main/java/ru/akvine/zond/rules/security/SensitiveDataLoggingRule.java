package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProtectedProperties;
import ru.akvine.zond.rules.support.SecretExposure;
import ru.akvine.zond.rules.support.Secrets;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SensitiveDataLoggingRule extends AbstractRule implements ProjectRule {
    // getPassword() -> password
    private static final Pattern GETTER = Pattern.compile("^get([A-Z].*)$");
    private static final Pattern SECURITY_OBJECT = Pattern.compile(".*(AuthenticationToken|Authentication)$");
    // Типы, значение которых попадает в лог как есть
    private static final Set<String> TEXT_TYPES = Set.of(
            "String", "CharSequence", "StringBuilder", "StringBuffer", "char[]", "byte[]", "Object", "Serializable");
    private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    // Место значения в шаблоне сообщения: "password = [{}]"
    private static final String PLACEHOLDER = "{}";
    // Подпись перед значением кончается разделителем, а не словом: "password = [", "token: '"
    private static final Pattern LABEL_TAIL = Pattern.compile("[\\s=:\\[({'\"<>-]+$");
    private static final Pattern LABEL_WORDS = Pattern.compile("([A-Za-z0-9]+[\\s_-]+)*[A-Za-z0-9]+$");
    private static final Pattern WORD_BREAK = Pattern.compile("[\\s_-]+|(?<=[a-z0-9])(?=[A-Z])");
    private static final int LABEL_LENGTH = 3;
    // Подписи, которые называют секрет однозначно. Слова token и credentials сюда не входят: токеном
    // называют и лексему разбора, и метку страницы, а по имени переменной их находит основная проверка
    private static final Set<String> LABEL_SECRETS = Set.of(
            "password", "passwd", "pwd", "passphrase", "secret", "cvv", "apikey");
    private static final Set<String> LABEL_PAIRS = Set.of(
            "api key", "private key", "secret key", "access key", "card number", "pin code");

    // Имена, которые ничего не говорят о значении: что это за значение, сообщает подпись в шаблоне. Имя со своим
    // смыслом (actionName, userId) подписи не уступает: "tried to check otp {}" с getActionName() - не код
    private static final Set<String> GENERIC_NAMES = Set.of(
            "value", "val", "v", "code", "data", "text", "str", "string", "s", "arg", "argument", "param",
            "parameter", "input", "result", "res", "body", "content", "payload", "it", "x", "tmp", "temp", "obj",
            "object", "o", "raw");

    // Одноразовый код подтверждения: otp, verificationCode, "authentication code = [{}]"
    private static final Set<String> ONE_TIME_WORDS = Set.of("otp", "totp");
    private static final String CODE = "code";
    private static final Set<String> CODE_PURPOSES = Set.of(
            "authentication", "authorization", "auth", "verification", "verify", "confirmation", "confirm", "sms",
            "otp", "totp",
            "mfa", "tfa", "security", "recovery", "restore", "reset", "registration", "activation", "login",
            "signin");
    // Класс, который сам занят одноразовыми кодами: в нем переменная code - это и есть код
    private static final Pattern ONE_TIME_CLASS = Pattern.compile(
            ".*(TwoFactor|Otp|OTP|OneTime|Mfa|MFA|Verification).*");
    // Числом одноразовый код бывает так же часто, как строкой
    private static final Set<String> NUMBER_TYPES = Set.of("int", "long", "Integer", "Long");

    // Обертки и общие контейнеры: по чтению entry.getValue() не понять, что именно прочитали
    private static final Set<String> CARRIER_TYPES = Set.of(
            "Object", "String", "Map", "Entry", "List", "Optional", "ResponseEntity", "HttpEntity", "JsonNode",
            "ObjectNode", "Pair", "Tuple", "AtomicReference");
    private static final Set<String> CARRIER_GETTERS = Set.of(
            "getValue", "getKey", "getBody", "getData", "getContent", "getResult", "getPayload", "getObject",
            "getLeft", "getRight", "getFirst", "getSecond", "getClass");
    private static final int LONG_CHAIN = 2;

    /**
     * Значение, которое сообщение само называет секретом либо которое оказалось одноразовым кодом
     *
     * @param oneTimeCode одноразовый код подтверждения, а не постоянный секрет
     */
    private record Named(Expression value, String label, boolean oneTimeCode) {
    }

    @Override
    public String code() {
        return RuleCodes.SENSITIVE_DATA_LOGGING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись в лог паролей, токенов, одноразовых кодов и номеров карт в открытом виде";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProtectedProperties properties = ProtectedProperties.of(sourceFiles);
        Map<String, String> secretReads = secretReads(sourceFiles);
        return sourceFiles.stream()
                .flatMap(sourceFile -> check(sourceFile, properties, secretReads).stream())
                .toList();
    }

    private List<Violation> check(
            SourceFile sourceFile, ProtectedProperties properties, Map<String, String> secretReads) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.findAll(MethodCallExpr.class)) {
            if (!Loggers.isLogCall(call)) {
                continue;
            }
            Optional<Expression> secret = Optional.empty();
            for (Expression argument : call.getArguments()) {
                if (secret.isEmpty()) {
                    secret = findSecret(argument, properties);
                }
            }
            if (secret.isEmpty()) {
                findNamed(call, properties)
                        .map(named -> describe(sourceFile, call, named))
                        .or(() -> findReadAsSecret(sourceFile, call, secretReads))
                        .ifPresent(violations::add);
                continue;
            }
            String text = secret.get().isNameExpr()
                    ? secret.get().asNameExpr().getNameAsString()
                    : secret.get().toString();
            Violation violation = violation(sourceFile, call,
                    "В лог пишется секретное значение '" + text + "': логи читает больше людей и систем,"
                            + " чем имеет право знать пароль или токен; уберите его из сообщения"
                            + " либо замаскируйте");
            // В лог пишут не строку, а объект: что из него попадет в запись, решает его toString()
            boolean object = LocalTypes.typeOf(secret.get()).filter(type -> !TEXT_TYPES.contains(type)).isPresent();
            violations.add(object ? violation.withConfidence(Confidence.SUSPICION) : violation);
        }
        return violations;
    }

    private Violation describe(SourceFile sourceFile, MethodCallExpr call, Named named) {
        if (named.oneTimeCode()) {
            return violation(sourceFile, call,
                    "В лог пишется одноразовый код '" + named.value() + "': тот, кто читает логи, подтвердит вход"
                            + " или операцию за пользователя; уберите код из сообщения. Если это отладочная"
                            + " отправка кода, убедитесь, что в рабочей среде она не включается")
                    .withLevel(ErrorLevel.MAJOR);
        }
        return violation(sourceFile, call,
                "В лог пишется значение '" + named.value() + "', которое само сообщение называет '" + named.label()
                        + "': логи читает больше людей и систем, чем имеет право знать пароль или ключ; уберите"
                        + " его из сообщения либо замаскируйте");
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // Переменная, поле или геттер с "секретным" именем, значение которых попадает в сообщение как есть.
    // Не считаются: зашифрованное или захешированное значение, длина и признак наличия (password != null),
    // значение, прошедшее через метод-обработку (mask(token)), и свойство, в которое кладут только хеш
    private Optional<Expression> findSecret(Expression argument, ProtectedProperties properties) {
        for (NameExpr name : argument.findAll(NameExpr.class)) {
            if (isSecretVariable(name.getNameAsString()) && !isSecurityObject(name)
                    && isRevealed(name, argument, properties)) {
                return Optional.of(name);
            }
        }
        for (FieldAccessExpr access : argument.findAll(FieldAccessExpr.class)) {
            if (isSecretVariable(access.getNameAsString()) && !isSecurityObject(access)
                    && isRevealed(access, argument, properties)) {
                return Optional.of(access);
            }
        }
        for (MethodCallExpr getter : argument.findAll(MethodCallExpr.class)) {
            Matcher matcher = GETTER.matcher(getter.getNameAsString());
            boolean isSecretGetter = getter.getArguments().isEmpty() && matcher.matches()
                    && Secrets.isSecretName(matcher.group(1));
            if (isSecretGetter && isRevealed(getter, argument, properties)) {
                return Optional.of(getter);
            }
        }
        return Optional.empty();
    }

    // UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken: объект Spring Security, его
    // toString() учетные данные не раскрывает ("credentials=[PROTECTED]")
    private boolean isSecurityObject(Expression value) {
        return LocalTypes.typeOf(value).filter(type -> SECURITY_OBJECT.matcher(type).matches()).isPresent();
    }

    // DeviceCredentialsType.ACCESS_TOKEN, TOKEN_HEADER: константа - имя или вид секрета, а не его значение
    private boolean isSecretVariable(String name) {
        return Secrets.isSecretName(name) && !CONSTANT_NAME.matcher(name).matches();
    }

    private boolean isRevealed(Expression secret, Expression argument, ProtectedProperties properties) {
        return SecretExposure.isExposed(secret, argument) && !properties.isProtectedValue(secret);
    }

    /**
     * {@code log.info("authentication code = [{}] to login = [{}]", code, login)}: у переменной имя общее, но
     * подпись в шаблоне говорит, что это за значение. Одноразовый код узнается еще по имени (otp,
     * verificationCode) и по классу, который кодами занят
     */
    private Optional<Named> findNamed(MethodCallExpr call, ProtectedProperties properties) {
        List<Expression> arguments = call.getArguments();
        int template = -1;
        for (int index = 0; index < arguments.size() && template < 0; index++) {
            if (StringLiterals.textOf(arguments.get(index)).isPresent()) {
                template = index;
            }
        }
        List<String> labels = template < 0 ? List.of() : labels(StringLiterals.textOf(arguments.get(template)).get());
        for (int index = template + 1; index < arguments.size(); index++) {
            Expression value = Nodes.unwrap(arguments.get(index));
            if (!isPlainValue(value) || isSecretVariable(nameOf(value)) || properties.isProtectedValue(value)
                    || Secrets.isProtectedName(nameOf(value))) {
                continue;
            }
            int position = index - template - 1;
            List<String> label = position < labels.size() ? words(labels.get(position)) : List.of();
            boolean number = LocalTypes.typeOf(value).filter(NUMBER_TYPES::contains).isPresent();
            boolean text = LocalTypes.typeOf(value).filter(type -> !TEXT_TYPES.contains(type)).isEmpty();
            if ((text || number) && isOneTimeCode(value, label)) {
                return Optional.of(new Named(value, String.join(" ", label), true));
            }
            if (text && isGenericName(value) && isSecretLabel(label)) {
                return Optional.of(new Named(value, String.join(" ", label), false));
            }
        }
        return Optional.empty();
    }

    // Переменная, поле или геттер без аргументов: значение попадает в запись как есть
    private boolean isPlainValue(Expression value) {
        return value.isNameExpr() || value.isFieldAccessExpr()
                || value.isMethodCallExpr() && value.asMethodCallExpr().getArguments().isEmpty()
                && GETTER.matcher(value.asMethodCallExpr().getNameAsString()).matches();
    }

    private String nameOf(Expression value) {
        if (value.isNameExpr()) {
            return value.asNameExpr().getNameAsString();
        }
        if (value.isFieldAccessExpr()) {
            return value.asFieldAccessExpr().getNameAsString();
        }
        Matcher getter = GETTER.matcher(value.asMethodCallExpr().getNameAsString());
        return getter.matches() ? getter.group(1) : value.asMethodCallExpr().getNameAsString();
    }

    // Текст шаблона перед каждым {}: по нему видно, как сообщение называет значение
    private List<String> labels(String template) {
        List<String> labels = new ArrayList<>();
        int from = 0;
        int placeholder = template.indexOf(PLACEHOLDER);
        while (placeholder >= 0) {
            labels.add(template.substring(from, placeholder));
            from = placeholder + PLACEHOLDER.length();
            placeholder = template.indexOf(PLACEHOLDER, from);
        }
        return labels;
    }

    // Последние слова подписи в нижнем регистре: "Send authentication code = [" -> send, authentication, code
    private List<String> words(String label) {
        Matcher tail = LABEL_WORDS.matcher(LABEL_TAIL.matcher(label).replaceAll(""));
        if (!tail.find()) {
            return List.of();
        }
        List<String> words = Arrays.stream(WORD_BREAK.split(tail.group()))
                .filter(word -> !word.isEmpty())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .toList();
        return words.subList(Math.max(0, words.size() - LABEL_LENGTH), words.size());
    }

    // "password = {}", "api key: {}"; "encrypted password = {}" и "password length = {}" секрета не называют
    private boolean isSecretLabel(List<String> label) {
        if (label.isEmpty() || Secrets.isProtectedName(String.join("_", label))) {
            return false;
        }
        String last = label.get(label.size() - 1);
        return LABEL_SECRETS.contains(last)
                || label.size() > 1 && LABEL_PAIRS.contains(label.get(label.size() - 2) + " " + last);
    }

    private boolean isOneTimeCode(Expression value, List<String> label) {
        List<String> name = words(nameOf(value));
        if (isOneTimeName(name) || isGenericName(value) && isOneTimeName(label)) {
            return true;
        }
        // sendAuthenticationCode(String login, String code) в TwoFactorNotificationSender
        return name.equals(List.of(CODE)) && value.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> ONE_TIME_CLASS.matcher(type.getNameAsString()).matches())
                .isPresent();
    }

    private boolean isGenericName(Expression value) {
        return GENERIC_NAMES.contains(nameOf(value).toLowerCase(Locale.ROOT));
    }

    // otp, userOtp, verificationCode, "sms code"; errorCode и "status code" сюда не попадают
    private boolean isOneTimeName(List<String> words) {
        if (words.isEmpty()) {
            return false;
        }
        String last = words.get(words.size() - 1);
        return ONE_TIME_WORDS.contains(last)
                || CODE.equals(last) && words.size() > 1 && CODE_PURPOSES.contains(words.get(words.size() - 2));
    }

    /**
     * Чтения, которые где-то в проекте принимают как секрет: {@code String token = payload.getMessage().getText()}.
     * То же чтение, записанное в лог в другом месте, выдает секрет, когда в нем приходит именно он
     *
     * @return подпись чтения -> место, где значение названо секретом
     */
    private Map<String, String> secretReads(List<SourceFile> sourceFiles) {
        Map<String, String> reads = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (VariableDeclarator variable : sourceFile.findAll(VariableDeclarator.class)) {
                String name = variable.getNameAsString();
                variable.getInitializer()
                        .filter(value -> isSecretVariable(name) && !TestClasses.isInside(variable))
                        .flatMap(this::readSignature)
                        .ifPresent(read -> reads.putIfAbsent(read, place(sourceFile, variable, name)));
            }
            for (AssignExpr assignment : sourceFile.findAll(AssignExpr.class)) {
                Expression target = Nodes.unwrap(assignment.getTarget());
                if ((target.isNameExpr() || target.isFieldAccessExpr()) && isSecretVariable(nameOf(target))
                        && !TestClasses.isInside(assignment)) {
                    String place = place(sourceFile, assignment, nameOf(target));
                    readSignature(assignment.getValue()).ifPresent(read -> reads.putIfAbsent(read, place));
                }
            }
        }
        return reads;
    }

    private String place(SourceFile sourceFile, Node node, String name) {
        return sourceFile.path().getFileName() + ":" + node.getBegin().map(position -> position.line).orElse(0)
                + " ('" + name + "')";
    }

    /**
     * Цепочка геттеров от переменной известного типа: {@code payload.getMessage().getText()} у Payload. Короткая
     * цепочка ({@code field.getText()}) совпадает только вместе с именем переменной: у одного типа одним
     * геттером читают разное
     */
    private Optional<String> readSignature(Expression expression) {
        Expression current = Nodes.unwrap(expression);
        List<String> getters = new ArrayList<>();
        while (current.isMethodCallExpr() && current.asMethodCallExpr().getArguments().isEmpty()
                && current.asMethodCallExpr().getScope().isPresent()
                && GETTER.matcher(current.asMethodCallExpr().getNameAsString()).matches()) {
            getters.add(0, current.asMethodCallExpr().getNameAsString());
            current = Nodes.unwrap(current.asMethodCallExpr().getScope().get());
        }
        if (getters.isEmpty() || !current.isNameExpr()
                || CARRIER_GETTERS.contains(getters.get(getters.size() - 1))) {
            return Optional.empty();
        }
        String root = current.asNameExpr().getNameAsString();
        return LocalTypes.typeOf(current)
                .filter(type -> !CARRIER_TYPES.contains(type))
                .map(type -> type + (getters.size() < LONG_CHAIN ? "#" + root : "") + "." + String.join(".", getters));
    }

    private Optional<Violation> findReadAsSecret(
            SourceFile sourceFile, MethodCallExpr call, Map<String, String> reads) {
        for (Expression argument : call.getArguments()) {
            Expression value = Nodes.unwrap(argument);
            Optional<Expression> read = value.isNameExpr() ? LocalTypes.findInitializer(value) : Optional.of(value);
            Optional<String> place = read.flatMap(this::readSignature).map(reads::get);
            if (place.isEmpty() || Secrets.isProtectedName(value.isNameExpr() ? nameOf(value) : "")) {
                continue;
            }
            return Optional.of(violation(sourceFile, call,
                    "В лог целиком пишется '" + value + "' - значение " + Nodes.unwrap(read.get()) + ", а в "
                            + place.get() + " это же значение принимают как секрет: когда в нем приходит секрет,"
                            + " он оказывается в логе открытым текстом; не пишите значение в лог целиком либо"
                            + " маскируйте его")
                    .withLevel(ErrorLevel.MAJOR));
        }
        return Optional.empty();
    }
}
