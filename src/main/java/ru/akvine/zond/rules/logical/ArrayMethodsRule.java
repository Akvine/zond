package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.List;
import java.util.Map;

@Component
public class ArrayMethodsRule extends AbstractRule {
    private static final String ARRAY_SUFFIX = "[]";

    // Метод Object, вызванный на массиве, и то, чем его надо заменить
    private static final Map<String, String> REPLACEMENTS = Map.of(
            "equals", "Arrays.equals(a, b)",
            "hashCode", "Arrays.hashCode(a)",
            "toString", "Arrays.toString(a)");

    @Override
    public String code() {
        return RuleCodes.ARRAY_METHODS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет equals, hashCode и toString, вызванные на массиве";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> REPLACEMENTS.containsKey(call.getNameAsString()))
                .filter(call -> call.getScope().filter(this::isArray).isPresent())
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' на массиве: массивы не переопределяют методы Object, поэтому сравниваются"
                                + " и выводятся ссылки, а не содержимое; используйте "
                                + REPLACEMENTS.get(call.getNameAsString())))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isArray(Expression scope) {
        return LocalTypes.typeOf(scope).filter(type -> type.endsWith(ARRAY_SUFFIX)).isPresent();
    }
}
