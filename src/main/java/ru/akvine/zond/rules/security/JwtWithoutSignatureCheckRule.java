package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Set;

@Component
public class JwtWithoutSignatureCheckRule extends AbstractRule {
    // jjwt: методы для токенов без подписи
    private static final Set<String> UNSIGNED_PARSERS =
            Set.of("parseClaimsJwt", "parsePlaintextJwt", "parseUnsecuredClaims", "parseUnsecuredContent");

    // auth0: JWT.decode(token) только раскодирует, Algorithm.none() отключает подпись
    private static final String JWT = "JWT";
    private static final String DECODE = "decode";
    private static final String ALGORITHM = "Algorithm";
    private static final String NONE = "none";

    @Override
    public String code() {
        return RuleCodes.JWT_WITHOUT_SIGNATURE_CHECK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет разбор JWT без проверки подписи";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(this::skipsSignature)
                .filter(call -> !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call.getNameAsString() + "(...)' читает JWT, не проверяя подпись: клиент может"
                                + " подставить в токен любые данные - чужой идентификатор, роль администратора;"
                                + " разбирайте токен методом, который проверяет подпись ключом"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private boolean skipsSignature(MethodCallExpr call) {
        return UNSIGNED_PARSERS.contains(call.getNameAsString())
                || MethodCalls.isCallOn(call, JWT, DECODE)
                || MethodCalls.isCallOn(call, ALGORITHM, NONE);
    }
}
