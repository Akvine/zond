package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
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
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class XPathInjectionRule extends AbstractTaintRule {
    private static final Set<String> XPATH_METHODS = Set.of("compile", "evaluate", "evaluateExpression");
    private static final Set<String> XPATH_TYPES = Set.of("XPath", "XPathExpression", "XPathFactory", "XPathEvaluator");
    private static final String XPATH = "xpath";

    // dom4j и JDOM принимают выражение прямо в этих методах - по имени метода ошибиться нельзя
    private static final Set<String> SELECT_METHODS = Set.of("selectNodes", "selectSingleNode", "createXPath");

    // Значение подставлено через переменную XPath, а не вклеено в текст выражения
    private static final String VARIABLE_RESOLVER = "setXPathVariableResolver";

    @Override
    public String code() {
        return RuleCodes.XPATH_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет выражения XPath, собранные из данных запроса";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (call.getArguments().isEmpty() || !isXPathCall(call) || usesVariables(call)) {
                continue;
            }
            // Выражение всегда идет первым аргументом; остальные - документ и тип результата
            Optional<Taint.Source> source = taint.findSource(call.getArgument(0));
            source.ifPresent(input -> violations.add(violation(sourceFile, call,
                    "Выражение XPath строится из данных '" + input + "': значение вида ' or '1'='1 изменит условие"
                            + " и вернет чужие узлы документа; подставляйте значения через переменные XPath"
                            + " (setXPathVariableResolver) либо проверяйте их по списку допустимых")));
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

    private boolean isXPathCall(MethodCallExpr call) {
        if (SELECT_METHODS.contains(call.getNameAsString())) {
            return true;
        }
        return XPATH_METHODS.contains(call.getNameAsString()) && call.getScope()
                .filter(scope -> LocalTypes.isAnyOf(scope, XPATH_TYPES,
                        () -> MethodCalls.receiverName(scope).toLowerCase(Locale.ROOT).contains(XPATH)))
                .isPresent();
    }

    private boolean usesVariables(Node node) {
        return Nodes.enclosingCallable(node)
                .filter(callable -> callable.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> VARIABLE_RESOLVER.equals(call.getNameAsString())))
                .isPresent();
    }
}
