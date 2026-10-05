package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Отвечает, раскрывается ли секрет в данном месте. Имя password само по себе ничего не доказывает:
 * значение могло быть зашифровано, а в сообщение могло попасть не оно, а его длина или признак наличия.
 */
@UtilityClass
public class SecretExposure {
    private static final Pattern WORD_BOUNDARY =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_.\\-\\s]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    // Слова в имени метода, после которого исходное значение восстановить нельзя либо оно скрыто:
    // encrypt(...), hashPassword(...), sha256Hex(...), maskToken(...)
    private static final Set<String> PROTECTING_WORDS = Set.of(
            "encrypt", "encrypted", "hash", "hashed", "mask", "masked", "digest", "hmac", "bcrypt", "sha", "md",
            "obfuscate", "redact", "anonymize", "anonymise", "censor", "fingerprint", "checksum", "sign");
    private static final Set<String> REVERSING_WORDS = Set.of("decrypt", "decode", "unmask", "decipher");

    // passwordEncoder.encode(...) защищает, а Base64.getEncoder().encode(...) и URLEncoder.encode(...) - нет
    private static final String ENCODE = "encode";
    private static final String GET_BYTES = "getBytes";
    private static final Set<String> REVERSIBLE_ENCODERS = Set.of("base64", "url", "hex", "json", "html");

    // Методы, результат которых содержит аргумент или само значение целиком
    private static final Set<String> PASSING_METHODS = Set.of(
            "toString", "valueOf", "format", "formatted", "concat", "join", "append", "trim", "strip",
            "toUpperCase", "toLowerCase", "of", "asList", "requireNonNull", "orElse");

    /**
     * @return true, если выражение - вызов, результат которого уже не раскрывает исходный секрет
     */
    public boolean isProtectingCall(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = value.asMethodCallExpr();
        boolean protects = false;
        for (String word : WORD_BOUNDARY.split(call.getNameAsString())) {
            String letters = DIGITS.matcher(word).replaceAll("").toLowerCase(Locale.ROOT);
            if (REVERSING_WORDS.contains(letters)) {
                return false;
            }
            protects |= PROTECTING_WORDS.contains(letters);
        }
        if (protects) {
            return true;
        }
        if (!call.getNameAsString().equals(ENCODE)) {
            return false;
        }
        String owner = call.getScope().map(scope -> scope.toString().toLowerCase(Locale.ROOT)).orElse("");
        return REVERSIBLE_ENCODERS.stream().noneMatch(owner::contains);
    }

    /**
     * @param secret выражение с секретным именем: переменная, поле либо геттер
     * @param root   выражение, которое выводится целиком: аргумент записи в лог
     * @return true, если значение секрета попадает в результат root как есть. password.length(),
     * password != null, mask(password) и credentials.getUsername() секрет не раскрывают
     */
    public boolean isExposed(Node secret, Expression root) {
        Node current = secret;
        while (current != root) {
            Node parent = current.getParentNode().orElse(null);
            if (parent == null) {
                return true;
            }
            if (!passesValue(parent, current)) {
                return false;
            }
            current = parent;
        }
        return true;
    }

    // Проходит ли значение child в результат parent без изменений
    private boolean passesValue(Node parent, Node child) {
        if (parent instanceof MethodCallExpr call) {
            // Неизвестный метод, получивший секрет, считается его обработкой: abbreviate(token), toDto(password)
            // Base64 и URL-кодирование обратимы: значение по-прежнему читается
            boolean isReversibleEncoding = call.getNameAsString().startsWith(ENCODE) || call.getNameAsString().equals(GET_BYTES);
            return (PASSING_METHODS.contains(call.getNameAsString()) || isReversibleEncoding) && !isProtectingCall(call);
        }
        if (parent instanceof ConditionalExpr conditional) {
            return conditional.getCondition() != child;
        }
        if (parent instanceof BinaryExpr binary) {
            return binary.getOperator() == BinaryExpr.Operator.PLUS;
        }
        return parent instanceof EnclosedExpr || parent instanceof CastExpr
                || parent instanceof ArrayCreationExpr || parent instanceof ArrayInitializerExpr;
    }
}
