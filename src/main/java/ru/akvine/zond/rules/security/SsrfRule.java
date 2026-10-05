package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
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
            if (takesAddress && !call.getArguments().isEmpty()) {
                report(sourceFile, taint, call, call.getArgument(0), violations);
            }
        }
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (ADDRESS_TYPES.contains(creation.getType().getNameAsString()) && !creation.getArguments().isEmpty()) {
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
        // "https://api.example.org/users/" + id: узел задан в коде, клиент влияет только на путь
        if (StringLiterals.textOf(leftmost(address)).isPresent()) {
            return;
        }
        taint.findSource(address).ifPresent(source -> violations.add(violation(sourceFile, node,
                "Адрес запроса берется из данных клиента '" + source + "': так можно заставить сервер обратиться"
                        + " к внутренним адресам (SSRF); сверяйте узел со списком разрешенных")));
    }

    private Expression leftmost(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        while (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            value = Nodes.unwrap(value.asBinaryExpr().getLeft());
        }
        return value;
    }
}
