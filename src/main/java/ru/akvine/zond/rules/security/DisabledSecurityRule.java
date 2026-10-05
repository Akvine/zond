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
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class DisabledSecurityRule extends AbstractRule {
    private static final String CSRF = "csrf";

    // Признаки API без cookie-сессий в той же конфигурации
    private static final Pattern STATELESS = Pattern.compile(
            "SessionCreationPolicy\\.STATELESS|oauth2ResourceServer|BearerToken|\\bjwt\\b|Jwt[A-Z]\\w*Filter", Pattern.CASE_INSENSITIVE);
    private static final String DISABLE = "disable";
    private static final String PERMIT_ALL = "permitAll";
    private static final String ANY_REQUEST = "anyRequest";

    @Override
    public String code() {
        return RuleCodes.DISABLED_SECURITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет отключенную защиту Spring Security: CSRF и доступ без аутентификации";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        boolean isStateless = STATELESS.matcher(sourceFile.unit().toString()).find();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();

            // Для API без сессий (токен в заголовке, STATELESS) защита от CSRF не нужна: подделывать нечего
            if (isCsrfDisabled(call) && !isStateless) {
                violations.add(violation(sourceFile, call,
                        "Защита от CSRF отключена: для API без cookie-сессий это допустимо, но при"
                                + " аутентификации через cookie чужой сайт сможет выполнять запросы от имени"
                                + " пользователя; убедитесь, что приложение не использует сессионные cookie"));
            }

            if (PERMIT_ALL.equals(name) && isCalledOn(call, ANY_REQUEST)) {
                violations.add(violation(sourceFile, call,
                        "anyRequest().permitAll(): все адреса приложения доступны без аутентификации;"
                                + " откройте только нужные пути, для остальных требуйте authenticated()"));
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

    // http.csrf(AbstractHttpConfigurer::disable), http.csrf(csrf -> csrf.disable()), http.csrf().disable()
    private boolean isCsrfDisabled(MethodCallExpr call) {
        if (CSRF.equals(call.getNameAsString())) {
            return call.getArguments().stream().anyMatch(this::disables);
        }
        return DISABLE.equals(call.getNameAsString()) && isCalledOn(call, CSRF);
    }

    private boolean disables(Expression argument) {
        if (argument.isMethodReferenceExpr()) {
            return DISABLE.equals(argument.asMethodReferenceExpr().getIdentifier());
        }
        return argument.findAll(MethodCallExpr.class).stream()
                .anyMatch(inner -> DISABLE.equals(inner.getNameAsString()));
    }

    private boolean isCalledOn(MethodCallExpr call, String previousMethod) {
        return call.getScope()
                .map(Nodes::unwrap)
                .filter(scope -> scope.isMethodCallExpr()
                        && previousMethod.equals(scope.asMethodCallExpr().getNameAsString()))
                .isPresent();
    }
}
