package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.ProtectedProperties;
import ru.akvine.zond.rules.support.SecretExposure;
import ru.akvine.zond.rules.support.Secrets;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SensitiveDataLoggingRule extends AbstractRule implements ProjectRule {
    // getPassword() -> password
    private static final Pattern GETTER = Pattern.compile("^get([A-Z].*)$");

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
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!Loggers.isLogCall(call)) {
                continue;
            }
            call.getArguments().stream()
                    .map(argument -> findSecret(argument, properties))
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(secret -> violations.add(violation(sourceFile, call,
                            "В лог пишется секретное значение '" + secret + "': логи читает больше людей и систем,"
                                    + " чем имеет право знать пароль или токен; уберите его из сообщения"
                                    + " либо замаскируйте")));
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
    private Optional<String> findSecret(Expression argument, ProtectedProperties properties) {
        for (NameExpr name : argument.findAll(NameExpr.class)) {
            if (Secrets.isSecretName(name.getNameAsString()) && isRevealed(name, argument, properties)) {
                return Optional.of(name.getNameAsString());
            }
        }
        for (FieldAccessExpr access : argument.findAll(FieldAccessExpr.class)) {
            if (Secrets.isSecretName(access.getNameAsString()) && isRevealed(access, argument, properties)) {
                return Optional.of(access.toString());
            }
        }
        for (MethodCallExpr getter : argument.findAll(MethodCallExpr.class)) {
            Matcher matcher = GETTER.matcher(getter.getNameAsString());
            boolean isSecretGetter = getter.getArguments().isEmpty() && matcher.matches()
                    && Secrets.isSecretName(matcher.group(1));
            if (isSecretGetter && isRevealed(getter, argument, properties)) {
                return Optional.of(getter.toString());
            }
        }
        return Optional.empty();
    }

    private boolean isRevealed(Expression secret, Expression argument, ProtectedProperties properties) {
        return SecretExposure.isExposed(secret, argument) && !properties.isProtectedValue(secret);
    }
}
