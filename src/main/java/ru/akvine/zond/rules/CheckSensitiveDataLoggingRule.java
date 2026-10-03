package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckSensitiveDataLoggingRule extends AbstractRule {
    // getPassword() -> password
    private static final Pattern GETTER = Pattern.compile("^get([A-Z].*)$");

    @Override
    public String code() {
        return RuleCodes.CHECK_SENSITIVE_DATA_LOGGING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись паролей, токенов и номеров карт в лог";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!Loggers.isLogCall(call)) {
                continue;
            }
            call.getArguments().stream()
                    .map(this::findSecret)
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

    // Переменная, поле или геттер с "секретным" именем. Текст самого сообщения не считается
    private Optional<String> findSecret(Expression argument) {
        for (NameExpr name : argument.findAll(NameExpr.class)) {
            if (Secrets.isSecretName(name.getNameAsString())) {
                return Optional.of(name.getNameAsString());
            }
        }
        for (FieldAccessExpr access : argument.findAll(FieldAccessExpr.class)) {
            if (Secrets.isSecretName(access.getNameAsString())) {
                return Optional.of(access.toString());
            }
        }
        for (MethodCallExpr getter : argument.findAll(MethodCallExpr.class)) {
            Matcher matcher = GETTER.matcher(getter.getNameAsString());
            if (getter.getArguments().isEmpty() && matcher.matches() && Secrets.isSecretName(matcher.group(1))) {
                return Optional.of(getter.toString());
            }
        }
        return Optional.empty();
    }
}
