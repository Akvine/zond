package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
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
import java.util.Set;

@Component
public class CheckDisabledSecurityRule extends AbstractRule {
    private static final String CSRF = "csrf";
    private static final String DISABLE = "disable";
    private static final String PERMIT_ALL = "permitAll";
    private static final String ANY_REQUEST = "anyRequest";
    private static final String CROSS_ORIGIN = "CrossOrigin";
    private static final String ANY_ORIGIN = "\"*\"";
    private static final Set<String> ORIGIN_METHODS =
            Set.of("allowedOrigins", "allowedOriginPatterns", "addAllowedOrigin", "addAllowedOriginPattern");

    @Override
    public String code() {
        return RuleCodes.CHECK_DISABLED_SECURITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет отключенную защиту Spring Security: CSRF, доступ без аутентификации,"
                + " CORS для любых источников";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();

            if (isCsrfDisabled(call)) {
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

            if (ORIGIN_METHODS.contains(name)
                    && call.getArguments().stream().anyMatch(argument -> ANY_ORIGIN.equals(argument.toString()))) {
                violations.add(reportAnyOrigin(sourceFile, call, name + "(\"*\")"));
            }
        }

        // @CrossOrigin без origins разрешает запросы с любого сайта
        for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
            if (CROSS_ORIGIN.equals(annotation.getName().getIdentifier())
                    && (annotation.isMarkerAnnotationExpr() || annotation.toString().contains(ANY_ORIGIN))) {
                violations.add(reportAnyOrigin(sourceFile, annotation, annotation.toString()));
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

    private Violation reportAnyOrigin(SourceFile sourceFile, Node node, String source) {
        return violation(sourceFile, node,
                "CORS для любых источников в '" + source + "': запросы к API сможет выполнять скрипт с любого"
                        + " сайта; перечислите разрешенные источники явно");
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
