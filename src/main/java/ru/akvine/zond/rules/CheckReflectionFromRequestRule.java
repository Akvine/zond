package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckReflectionFromRequestRule extends AbstractTaintRule {
    private static final Set<String> REFLECTION_METHODS = Set.of(
            "forName", "loadClass", "getMethod", "getDeclaredMethod", "getField", "getDeclaredField");

    @Override
    public String code() {
        return RuleCodes.CHECK_REFLECTION_FROM_REQUEST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет рефлексию по имени класса или метода, взятому из данных запроса";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!REFLECTION_METHODS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
                continue;
            }
            taint.findSource(call.getArgument(0)).ifPresent(source -> violations.add(violation(sourceFile, call,
                    "Имя для '" + call.getNameAsString() + "' берется из данных клиента '" + source + "':"
                            + " клиент сможет создать любой класс или вызвать любой метод приложения;"
                            + " выбирайте из заранее заданного набора")));
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
}
