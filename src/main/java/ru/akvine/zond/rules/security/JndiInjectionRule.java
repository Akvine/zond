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
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class JndiInjectionRule extends AbstractTaintRule {
    private static final Set<String> LOOKUP_METHODS = Set.of("lookup", "lookupLink");
    private static final Set<String> CONTEXT_TYPES = Set.of(
            "Context", "InitialContext", "DirContext", "InitialDirContext", "LdapContext", "InitialLdapContext",
            "JndiTemplate", "JndiLocatorDelegate", "JndiObjectFactoryBean");
    private static final Set<String> CONTEXT_NAMES = Set.of("ctx", "context", "initialcontext", "jndi", "jnditemplate");

    @Override
    public String code() {
        return RuleCodes.JNDI_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет поиск в JNDI по имени, которое пришло из запроса";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!LOOKUP_METHODS.contains(call.getNameAsString()) || call.getArguments().size() != 1 || !isJndiContext(call)) {
                continue;
            }
            Optional<Taint.Source> source = taint.findSource(call.getArgument(0));
            source.ifPresent(input -> violations.add(violation(sourceFile, call,
                    "Имя для поиска в JNDI берется из данных '" + input + "': значение вида ldap://чужой-сервер/x"
                            + " заставит приложение загрузить и выполнить чужой объект; не передавайте в lookup"
                            + " данные клиента либо сверяйте имя со списком допустимых")));
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

    // new InitialContext().lookup(name), context.lookup(name), jndiTemplate.lookup(name)
    private boolean isJndiContext(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        if (scope.get().isObjectCreationExpr()) {
            return CONTEXT_TYPES.contains(scope.get().asObjectCreationExpr().getType().getNameAsString());
        }
        return LocalTypes.isAnyOf(scope.get(), CONTEXT_TYPES,
                () -> CONTEXT_NAMES.contains(MethodCalls.receiverName(scope.get()).toLowerCase(Locale.ROOT)));
    }
}
