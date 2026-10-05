package ru.akvine.zond.rules.security;

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
import java.util.Set;

@Component
public class RegexFromRequestRule extends AbstractTaintRule {
    private static final String PATTERN = "Pattern";
    private static final String COMPILE = "compile";
    private static final String MATCHES = "matches";

    // У String шаблон - первый аргумент этих методов
    private static final Set<String> STRING_REGEX_METHODS = Set.of("replaceAll", "replaceFirst", "split");

    @Override
    public String code() {
        return RuleCodes.REGEX_FROM_REQUEST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет регулярные выражения, шаблон которых приходит из данных запроса";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!takesRegex(call)) {
                continue;
            }
            // Pattern.quote(value) превращает значение в обычный текст - данные клиента через него не проходят
            taint.findSource(call.getArgument(0)).ifPresent(source -> violations.add(violation(sourceFile, call,
                    "Шаблон регулярного выражения берется из данных клиента '" + source + "': выражением вроде"
                            + " (a+)+$ можно надолго занять поток (ReDoS); экранируйте значение через"
                            + " Pattern.quote(...) или не принимайте шаблон от клиента")));
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

    private boolean takesRegex(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (call.getArguments().isEmpty() || call.getScope().isEmpty()) {
            return false;
        }
        // text.matches(regex) - один аргумент; passwordEncoder.matches(raw, encoded) к шаблонам не относится
        return MethodCalls.isCallOn(call, PATTERN, COMPILE)
                || STRING_REGEX_METHODS.contains(method)
                || MATCHES.equals(method) && call.getArguments().size() == 1;
    }
}
