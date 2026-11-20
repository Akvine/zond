package ru.akvine.zond.rules.security;

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
import ru.akvine.zond.rules.support.ProtectedProperties;
import ru.akvine.zond.rules.support.SecretExposure;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.List;
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

    @Override
    public String code() {
        return RuleCodes.SENSITIVE_DATA_LOGGING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись в лог паролей, токенов и номеров карт в открытом виде";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProtectedProperties properties = ProtectedProperties.of(sourceFiles);
        return sourceFiles.stream().flatMap(sourceFile -> check(sourceFile, properties).stream()).toList();
    }

    private List<Violation> check(SourceFile sourceFile, ProtectedProperties properties) {
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
}
