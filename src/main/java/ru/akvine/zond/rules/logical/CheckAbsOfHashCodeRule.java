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
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Set;

@Component
public class CheckAbsOfHashCodeRule extends AbstractRule {
    private static final String MATH = "Math";
    private static final String ABS = "abs";

    // Методы, которые могут вернуть Integer.MIN_VALUE или Long.MIN_VALUE
    private static final Set<String> FULL_RANGE_METHODS = Set.of("hashCode", "nextInt", "nextLong", "hash");

    @Override
    public String code() {
        return RuleCodes.CHECK_ABS_OF_HASH_CODE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Math.abs() от hashCode() и случайных чисел";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> MethodCalls.isCallOn(call, MATH, ABS) && call.getArguments().size() == 1)
                .filter(call -> isFullRange(call.getArgument(0)))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': у Integer.MIN_VALUE нет положительной пары, Math.abs вернет его же -"
                                + " отрицательное число, и остаток от деления даст отрицательный индекс;"
                                + " используйте Math.floorMod(value, n)"))
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

    // key.hashCode(), random.nextInt() без границы, Objects.hash(...)
    private boolean isFullRange(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (!value.isMethodCallExpr() || !FULL_RANGE_METHODS.contains(value.asMethodCallExpr().getNameAsString())) {
            return false;
        }
        // nextInt(bound) возвращает неотрицательное число
        boolean isBoundedRandom = value.asMethodCallExpr().getNameAsString().startsWith("next")
                && !value.asMethodCallExpr().getArguments().isEmpty();
        return !isBoundedRandom;
    }
}
