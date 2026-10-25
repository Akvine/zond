package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * request.getHeader("X-Forwarded-For") как адрес клиента: заголовок присылает сам клиент, и подставить в него
 * можно любой адрес. Ограничение числа попыток входа, список разрешенных адресов или блокировка по IP так
 * обходятся одной строкой в запросе. Доверять заголовку можно, только если его выставил свой прокси
 */
@Component
public class TrustedForwardedHeaderRule extends AbstractRule {
    private static final Set<String> HEADER_METHODS = Set.of("getHeader", "getHeaders", "getFirst");
    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "x-forwarded-for", "x-real-ip", "x-client-ip", "true-client-ip", "forwarded", "proxy-client-ip",
            "wl-proxy-client-ip", "http_client_ip", "http_x_forwarded_for", "x-cluster-client-ip");
    // Решение о доступе по адресу: блокировка, предел попыток, список разрешенных. Слова сравниваются целиком:
    // rate в generate и lock в clock - не о доступе
    private static final Set<String> SECURITY_WORDS = Set.of(
            "allow", "allowed", "block", "blocked", "deny", "denied", "ban", "banned", "limit", "limiter", "rate",
            "throttle", "whitelist", "blacklist", "white", "black", "trusted", "trust", "auth", "authenticate",
            "verify", "attempt", "attempts", "lock", "locked", "permit", "forbid", "forbidden", "guard", "captcha",
            "brute", "acquire");
    private static final Pattern WORD_BOUNDARY = Pattern.compile("[^A-Za-z]+|(?<=[a-z])(?=[A-Z])");
    private static final Set<String> COMPARISONS = Set.of(
            "equals", "equalsIgnoreCase", "contains", "startsWith", "matches", "containsKey", "anyMatch");

    @Override
    public String code() {
        return RuleCodes.TRUSTED_FORWARDED_HEADER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет адрес клиента из X-Forwarded-For и подобных заголовков, которые клиент"
                + " задает сам";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        Set<Node> reportedMethods = new HashSet<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<String> header = forwardedHeader(call);
            Optional<Node> method = Nodes.enclosingCallable(call);
            if (header.isEmpty() || method.isEmpty() || TestClasses.isInside(call)
                    || !reportedMethods.add(method.get())) {
                continue;
            }
            Set<String> derived = derivedVariables(call);
            Optional<Confidence> confidence = confidence(call, derived, method.get());
            confidence.ifPresent(level -> violations.add(violation(sourceFile, call,
                    "Адрес клиента берется из заголовка '" + header.get() + "', а его присылает сам клиент: подставив"
                            + " чужой адрес, он обойдет блокировку, предел попыток или список разрешенных адресов;"
                            + " доверяйте заголовку только от своего прокси - настройте"
                            + " server.forward-headers-strategy либо RemoteIpValve с адресами прокси и берите"
                            + " request.getRemoteAddr()")
                    .withConfidence(level)));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private Optional<String> forwardedHeader(MethodCallExpr call) {
        if (!HEADER_METHODS.contains(call.getNameAsString()) || call.getArguments().isEmpty()
                || !call.getArgument(0).isStringLiteralExpr()) {
            return Optional.empty();
        }
        String header = call.getArgument(0).asStringLiteralExpr().asString();
        return FORWARDED_HEADERS.contains(header.toLowerCase(Locale.ROOT)) ? Optional.of(header) : Optional.empty();
    }

    // String ip = request.getHeader("X-Forwarded-For"); ip = ip.split(",")[0]
    private Set<String> derivedVariables(MethodCallExpr call) {
        Set<String> names = new HashSet<>();
        Node parent = call.getParentNode().orElse(null);
        if (parent instanceof VariableDeclarator variable) {
            names.add(variable.getNameAsString());
        } else if (parent instanceof AssignExpr assignment && assignment.getTarget().isNameExpr()) {
            names.add(assignment.getTarget().asNameExpr().getNameAsString());
        }
        return names;
    }

    /**
     * @return вероятно - адрес из заголовка решает, пускать ли клиента; подозрение - метод отдает его наружу
     * (getIpAddr), и как его используют, отсюда не видно; пусто - адрес только пишется в лог
     */
    private Optional<Confidence> confidence(MethodCallExpr call, Set<String> derived, Node method) {
        List<NameExpr> usages = method.findAll(NameExpr.class).stream()
                .filter(name -> derived.contains(name.getNameAsString()))
                .toList();
        boolean decides = usages.stream().anyMatch(this::isSecurityDecision) || isSecurityDecision(call);
        if (decides) {
            return Optional.of(Confidence.PROBABLE);
        }
        boolean returned = isReturned(call) || usages.stream().anyMatch(this::isReturned);
        boolean onlyLogged = !usages.isEmpty() && usages.stream().allMatch(this::isLogged) || isLogged(call);
        return returned || !onlyLogged ? Optional.of(Confidence.SUSPICION) : Optional.empty();
    }

    // if (blocked.contains(ip)), rateLimiter.tryAcquire(ip), whitelist.contains(ip)
    private boolean isSecurityDecision(Expression usage) {
        Node current = usage.getParentNode().orElse(null);
        while (current != null && !(current instanceof BodyDeclaration<?>)) {
            if (current instanceof MethodCallExpr call && (hasSecurityWord(call.getNameAsString())
                    || COMPARISONS.contains(call.getNameAsString())
                    && call.getScope().filter(scope -> hasSecurityWord(scope.toString())).isPresent())) {
                return true;
            }
            if (current instanceof IfStmt branch) {
                return hasSecurityWord(branch.getCondition().toString());
            }
            if (current instanceof ConditionalExpr ternary) {
                return hasSecurityWord(ternary.getCondition().toString());
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean hasSecurityWord(String text) {
        return Arrays.stream(WORD_BOUNDARY.split(text))
                .anyMatch(word -> SECURITY_WORDS.contains(word.toLowerCase(Locale.ROOT)));
    }

    private boolean isReturned(Expression usage) {
        return usage.findAncestor(ReturnStmt.class).isPresent();
    }

    private boolean isLogged(Expression usage) {
        return usage.findAncestor(MethodCallExpr.class).filter(Loggers::isLogCall).isPresent();
    }
}
