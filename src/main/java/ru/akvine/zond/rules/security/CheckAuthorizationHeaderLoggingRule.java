package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class CheckAuthorizationHeaderLoggingRule extends AbstractRule {
    // request.getHeader("Authorization"), HttpHeaders.AUTHORIZATION, authHeader, bearerToken
    private static final Pattern AUTHORIZATION =
            Pattern.compile(".*(authorization|authheader|bearer).*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public String code() {
        return RuleCodes.CHECK_AUTHORIZATION_HEADER_LOGGING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись заголовка Authorization в лог";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!Loggers.isLogCall(call)) {
                continue;
            }
            findLoggedHeader(call).ifPresent(header -> violations.add(violation(sourceFile, call,
                    "В лог пишется '" + header + "': заголовок авторизации содержит токен или пароль, из лога его"
                            + " прочтет любой, у кого есть доступ к логам; не логируйте его либо маскируйте")));
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

    // Само слово Authorization в тексте сообщения - не утечка; ищем значение среди подставляемых аргументов
    private Optional<String> findLoggedHeader(MethodCallExpr call) {
        return call.getArguments().stream()
                .map(Nodes::unwrap)
                .filter(argument -> StringLiterals.textOf(argument).isEmpty())
                .filter(argument -> containsHeader(argument))
                .map(Expression::toString)
                .findFirst();
    }

    private boolean containsHeader(Expression argument) {
        // В конкатенации "Authorization: " + value литерал не считается, считается то, что к нему приклеено
        return argument.findAll(Expression.class).stream()
                .filter(part -> !part.isStringLiteralExpr() && !part.isBinaryExpr())
                .anyMatch(part -> AUTHORIZATION.matcher(part.toString()).matches());
    }
}
