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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * request.getSession().setAttribute("userId", request.getParameter("id")): значение, которое прислал клиент,
 * лежит в сессии рядом с тем, что положил сам сервер. Код, который потом читает сессию, считает его проверенным -
 * так клиент подменяет роль, идентификатор пользователя или признак пройденной проверки (CWE-501)
 */
@Component
public class TrustBoundaryViolationRule extends AbstractTaintRule {
    private static final Set<String> SESSION_WRITES = Set.of("setAttribute", "putValue");
    private static final Set<String> SESSION_TYPES = Set.of("HttpSession", "WebSession", "Session");
    private static final String GET_SESSION = "getSession";
    private static final Pattern SESSION_NAME = Pattern.compile("(?i).*session");

    @Override
    public String code() {
        return RuleCodes.TRUST_BOUNDARY_VIOLATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет данные запроса, которые без проверки кладут в HTTP-сессию";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!SESSION_WRITES.contains(call.getNameAsString()) || call.getArguments().size() != 2
                    || call.getScope().filter(this::isSession).isEmpty()) {
                continue;
            }
            // И имя атрибута, и значение: подставив имя, клиент перезапишет чужой атрибут
            call.getArguments().stream()
                    .map(taint::findSource)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(input -> violations.add(violation(sourceFile, call,
                            "В сессию кладутся данные клиента '" + input + "' без проверки: код, который потом"
                                    + " читает сессию, сочтет их установленными сервером - так подменяют"
                                    + " идентификатор пользователя, роль или признак пройденной проверки;"
                                    + " проверьте значение по списку допустимых либо храните в сессии только то,"
                                    + " что вычислил сервер")));
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

    // request.getSession(), HttpSession session, переменная session
    private boolean isSession(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return GET_SESSION.equals(value.asMethodCallExpr().getNameAsString());
        }
        return LocalTypes.isAnyOf(value, SESSION_TYPES,
                () -> value.isNameExpr() && SESSION_NAME.matcher(value.asNameExpr().getNameAsString()).matches());
    }
}
