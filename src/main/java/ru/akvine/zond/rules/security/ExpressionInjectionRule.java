package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ExpressionInjectionRule extends AbstractTaintRule {
    private static final String PARSE_EXPRESSION = "parseExpression";
    private static final Set<String> XPATH_METHODS = Set.of("evaluate", "compile");
    private static final String SEARCH = "search";

    private static final Pattern XPATH_RECEIVER = Pattern.compile(".*xpath.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LDAP_RECEIVER = Pattern.compile(".*ldap.*|.*(dircontext|ctx)$", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.EXPRESSION_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет выражения SpEL, XPath и фильтры LDAP, собранные из данных запроса";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<Expression> expression = findExpression(call);
            if (expression.isEmpty()) {
                continue;
            }
            taint.findSource(expression.get()).ifPresent(source -> violations.add(violation(sourceFile, call,
                    "Выражение для '" + call.getNameAsString() + "' собирается из данных клиента '" + source + "':"
                            + " клиент сможет выполнить свой код или получить чужие данные; передавайте значения"
                            + " параметрами выражения, а не частью его текста")));
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

    /**
     * @return аргумент с текстом выражения: SpEL, XPath либо фильтр LDAP
     */
    private Optional<Expression> findExpression(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        if (PARSE_EXPRESSION.equals(method)) {
            return Optional.of(call.getArgument(0));
        }

        String receiver = call.getScope().map(MethodCalls::receiverName).orElse("");
        if (XPATH_METHODS.contains(method) && XPATH_RECEIVER.matcher(receiver).matches()) {
            return Optional.of(call.getArgument(0));
        }
        // ctx.search(base, filter, controls): фильтр идет вторым
        if (SEARCH.equals(method) && call.getArguments().size() > 1 && LDAP_RECEIVER.matcher(receiver).matches()) {
            return Optional.of(call.getArgument(1));
        }
        return Optional.empty();
    }
}
