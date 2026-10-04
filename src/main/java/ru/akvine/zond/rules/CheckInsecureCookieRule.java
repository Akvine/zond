package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class CheckInsecureCookieRule extends AbstractRule {
    private static final String COOKIE = "Cookie";
    private static final String RESPONSE_COOKIE = "ResponseCookie";
    private static final String FROM = "from";
    private static final String TRUE = "(true)";

    @Override
    public String code() {
        return RuleCodes.CHECK_INSECURE_COOKIE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет cookie без признаков HttpOnly и Secure";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        // Cookie cookie = new Cookie(...); cookie.setHttpOnly(true); cookie.setSecure(true);
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            boolean createsCookie = variable.getInitializer()
                    .map(Nodes::unwrap)
                    .filter(Expression::isObjectCreationExpr)
                    .filter(value -> COOKIE.equals(value.asObjectCreationExpr().getType().getNameAsString()))
                    .isPresent();
            Optional<Node> callable = Nodes.enclosingCallable(variable);
            if (createsCookie && callable.isPresent() && !TestClasses.isInside(variable)) {
                String setup = callable.get().toString();
                String name = variable.getNameAsString();
                describeMissing(setup.contains(name + ".setHttpOnly" + TRUE), setup.contains(name + ".setSecure" + TRUE))
                        .ifPresent(missing -> violations.add(report(sourceFile, variable, missing)));
            }
        }

        // ResponseCookie.from(...).httpOnly(true).secure(true).build()
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (MethodCalls.isCallOn(call, RESPONSE_COOKIE, FROM) && !TestClasses.isInside(call)) {
                String chain = topOfChain(call).toString();
                describeMissing(chain.contains(".httpOnly" + TRUE), chain.contains(".secure" + TRUE))
                        .ifPresent(missing -> violations.add(report(sourceFile, call, missing)));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
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

    private Optional<String> describeMissing(boolean httpOnly, boolean secure) {
        if (httpOnly && secure) {
            return Optional.empty();
        }
        return Optional.of(httpOnly ? "Secure" : secure ? "HttpOnly" : "HttpOnly и Secure");
    }

    private Violation report(SourceFile sourceFile, Node node, String missing) {
        return violation(sourceFile, node,
                "Cookie создается без " + missing + ": без HttpOnly ее прочитает скрипт на странице, без Secure"
                        + " она уйдет по незашифрованному соединению; задайте оба признака");
    }

    private Node topOfChain(MethodCallExpr call) {
        Node top = call;
        while (top.getParentNode().filter(parent -> parent instanceof MethodCallExpr).isPresent()) {
            top = top.getParentNode().get();
        }
        return top;
    }
}
