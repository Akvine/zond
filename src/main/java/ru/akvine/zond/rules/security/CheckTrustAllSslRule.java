package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class CheckTrustAllSslRule extends AbstractRule {
    // Методы X509TrustManager: пустое тело означает "доверять любому сертификату"
    private static final Set<String> TRUST_CHECKS = Set.of("checkServerTrusted", "checkClientTrusted");

    // Готовые "доверять всем" из библиотек
    private static final Set<String> TRUST_ALL_NAMES = Set.of(
            "NoopHostnameVerifier", "TrustAllStrategy", "TrustSelfSignedStrategy", "InsecureTrustManagerFactory",
            "ALLOW_ALL_HOSTNAME_VERIFIER");

    private static final Set<String> HOSTNAME_VERIFIER_SETTERS =
            Set.of("setHostnameVerifier", "setDefaultHostnameVerifier", "hostnameVerifier", "setSSLHostnameVerifier");

    @Override
    public String code() {
        return RuleCodes.CHECK_TRUST_ALL_SSL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет отключенную проверку SSL-сертификатов и имени хоста";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            boolean isEmptyCheck = TRUST_CHECKS.contains(method.getNameAsString())
                    && method.getBody().filter(body -> body.getStatements().isEmpty()).isPresent();
            if (isEmptyCheck) {
                violations.add(report(sourceFile, method, "пустой " + method.getNameAsString() + "(...)"));
            }
        }

        for (SimpleName name : sourceFile.unit().findAll(SimpleName.class)) {
            if (TRUST_ALL_NAMES.contains(name.getIdentifier())) {
                violations.add(report(sourceFile, name, name.getIdentifier()));
            }
        }

        // setHostnameVerifier((host, session) -> true)
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (HOSTNAME_VERIFIER_SETTERS.contains(call.getNameAsString())
                    && call.getArguments().stream().anyMatch(argument ->
                    argument.isLambdaExpr() && alwaysTrue(argument.asLambdaExpr()))) {
                violations.add(report(sourceFile, call, call.getNameAsString() + "(... -> true)"));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
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

    private Violation report(SourceFile sourceFile, Node node, String source) {
        return violation(sourceFile, node,
                "Проверка SSL отключена (" + source + "): соединение примет любой сертификат, и тот, кто"
                        + " встанет между приложением и сервером, прочитает и подменит данные; добавьте нужный"
                        + " сертификат в truststore вместо отключения проверки");
    }

    private boolean alwaysTrue(LambdaExpr lambda) {
        return lambda.getExpressionBody()
                .filter(body -> body.isBooleanLiteralExpr() && body.asBooleanLiteralExpr().getValue())
                .isPresent();
    }
}
