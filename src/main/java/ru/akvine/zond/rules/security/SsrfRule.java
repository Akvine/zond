package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class SsrfRule extends AbstractTaintRule {
    // Методы, первым аргументом которых идет адрес: RestTemplate, WebClient, RestClient
    private static final Set<String> REQUEST_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation", "patchForObject",
            "exchange", "uri");
    private static final Set<String> ADDRESS_TYPES = Set.of("URL", "URI");
    private static final String URI = "URI";
    private static final String CREATE = "create";
    private static final String PATH_SEPARATOR = "/";
    // Разбор адреса без обращения по нему
    private static final Set<String> INSPECTION_METHODS = Set.of(
            "getHost", "getScheme", "getPort", "getPath", "getQuery", "getAuthority", "getFragment", "getUserInfo",
            "getRawPath", "getRawQuery", "getProtocol", "getDefaultPort", "isAbsolute", "isOpaque", "equals");

    @Override
    public String code() {
        return RuleCodes.SSRF_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет HTTP-запросы на адрес, взятый из данных запроса (SSRF)";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean takesAddress = REQUEST_METHODS.contains(call.getNameAsString())
                    || MethodCalls.isCallOn(call, URI, CREATE);
            if (takesAddress && !call.getArguments().isEmpty() && !isOnlyInspected(call)) {
                report(sourceFile, taint, call, call.getArgument(0), violations);
            }
        }
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            // new URI(link); без использования результата - проверка формата адреса, а не обращение по нему
            if (ADDRESS_TYPES.contains(creation.getType().getNameAsString()) && !creation.getArguments().isEmpty()
                    && !(creation.getParentNode().orElse(null) instanceof ExpressionStmt) && !isOnlyInspected(creation)) {
                report(sourceFile, taint, creation, creation.getArgument(0), violations);
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

    private void report(
            SourceFile sourceFile, Taint taint, Node node, Expression address, List<Violation> violations) {
        // "https://api.example.org/users/" + id: узел задан в коде, клиент влияет только на путь.
        // baseUrl + "/async-result/" + taskId: то же, если baseUrl - не данные клиента
        Expression first = leftmost(address);
        if (StringLiterals.textOf(first).isPresent()
                || taint.findSource(first).isEmpty() && pathAfter(first).isPresent()) {
            return;
        }
        taint.findSource(address).ifPresent(source -> violations.add(violation(sourceFile, node,
                "Адрес запроса берется из данных клиента '" + source + "': так можно заставить сервер обратиться"
                        + " к внутренним адресам (SSRF); сверяйте узел со списком разрешенных")));
    }

    // Литерал сразу за первой частью склейки, если он начинается с "/": base + "/path/" + id
    private Optional<String> pathAfter(Expression first) {
        Node parent = first.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        if (!(parent instanceof BinaryExpr concatenation) || concatenation.getOperator() != BinaryExpr.Operator.PLUS) {
            return Optional.empty();
        }
        return StringLiterals.textOf(concatenation.getRight()).filter(text -> text.startsWith(PATH_SEPARATOR));
    }

    // URI uri = new URI(state); if (uri.getHost() == null) ...: адрес только разбирают и проверяют, запроса по нему нет
    private boolean isOnlyInspected(Expression address) {
        Node parent = address.getParentNode().orElse(null);
        String variable = null;
        if (parent instanceof VariableDeclarator declarator) {
            variable = declarator.getNameAsString();
        } else if (parent instanceof AssignExpr assign && assign.getValue() == address && assign.getTarget().isNameExpr()) {
            variable = assign.getTarget().asNameExpr().getNameAsString();
        }
        if (variable == null) {
            return false;
        }
        String name = variable;
        Optional<Node> scope = address.findAncestor(CallableDeclaration.class).map(callable -> (Node) callable);
        return scope.isPresent() && scope.get().findAll(NameExpr.class).stream()
                .filter(usage -> usage.getNameAsString().equals(name))
                .filter(usage -> !(usage.getParentNode().orElse(null) instanceof AssignExpr assign && assign.getTarget() == usage))
                .allMatch(this::isInspection);
    }

    private boolean isInspection(NameExpr usage) {
        Node parent = usage.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == usage).isPresent()) {
            return INSPECTION_METHODS.contains(call.getNameAsString());
        }
        return parent instanceof BinaryExpr comparison
                && (comparison.getLeft().isNullLiteralExpr() || comparison.getRight().isNullLiteralExpr());
    }

    private Expression leftmost(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        while (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            value = Nodes.unwrap(value.asBinaryExpr().getLeft());
        }
        return value;
    }
}
