package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class LdapInjectionRule extends AbstractTaintRule {
    private static final Set<String> SEARCH_METHODS =
            Set.of("search", "searchForObject", "searchForContext", "authenticate", "find", "findOne");
    private static final Set<String> LDAP_TYPES = Set.of(
            "DirContext", "InitialDirContext", "LdapContext", "InitialLdapContext", "LdapTemplate", "LdapOperations",
            "LdapClient");
    private static final String LDAP = "ldap";
    private static final String DIR_CONTEXT = "dircontext";

    // После этих вызовов спецсимволы фильтра и имени безвредны
    private static final Set<String> ESCAPES = Set.of(
            "filterEncode", "nameEncode", "escapeLDAPSearchFilter", "encodeForLDAP", "encodeForDN", "escapeDn",
            "escapeFilter");
    // Построители фильтра и имени экранируют значения сами
    private static final Set<String> SAFE_BUILDERS = Set.of(
            "EqualsFilter", "AndFilter", "OrFilter", "LikeFilter", "LdapQueryBuilder", "LdapNameBuilder");

    @Override
    public String code() {
        return RuleCodes.LDAP_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет фильтры и имена LDAP, собранные из данных запроса без экранирования";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!SEARCH_METHODS.contains(call.getNameAsString()) || !isLdap(call) || isEscaped(call)) {
                continue;
            }
            // Фильтр и база поиска - строки; данные запроса опасны в любой из них
            call.getArguments().stream()
                    .filter(this::isText)
                    .map(taint::findSource)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(input -> violations.add(violation(sourceFile, call,
                            "Запрос к LDAP строится из данных '" + input + "' без экранирования: значение вида"
                                    + " *)(uid=*) изменит условие поиска и вернет чужие записи либо обойдет"
                                    + " проверку пароля; экранируйте значение (LdapEncoder.filterEncode) либо"
                                    + " стройте фильтр через EqualsFilter и LdapQueryBuilder")));
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

    private boolean isLdap(MethodCallExpr call) {
        return call.getScope()
                .filter(scope -> LocalTypes.isAnyOf(scope, LDAP_TYPES, () -> {
                    String name = MethodCalls.receiverName(scope).toLowerCase(Locale.ROOT);
                    return name.contains(LDAP) || name.contains(DIR_CONTEXT);
                }))
                .isPresent();
    }

    // Объект фильтра или запроса собран построителем - строки в нем уже экранированы
    private boolean isText(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isObjectCreationExpr() || value.isLambdaExpr() || value.isMethodReferenceExpr()) {
            return false;
        }
        return LocalTypes.typeOf(value).filter(type -> !"String".equals(type)).isEmpty();
    }

    private boolean isEscaped(Node node) {
        return Nodes.enclosingCallable(node)
                .filter(callable -> callable.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> ESCAPES.contains(call.getNameAsString()))
                        || callable.findAll(ObjectCreationExpr.class).stream()
                        .anyMatch(creation -> SAFE_BUILDERS.contains(creation.getType().getNameAsString())))
                .isPresent();
    }
}
