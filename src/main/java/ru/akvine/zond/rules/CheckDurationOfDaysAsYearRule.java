package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckDurationOfDaysAsYearRule implements Rule {
    private static final String DURATION = "Duration";
    private static final String OF_DAYS = "ofDays";
    private static final Set<String> DAYS_IN_YEAR = Set.of("365", "366");

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_DURATION_OF_DAYS_AS_YEAR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Duration.ofDays(365) в роли года";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> MethodCalls.isCallOn(call, DURATION, OF_DAYS))
                .filter(call -> call.getArguments().size() == 1 && isYearInDays(call.getArgument(0)))
                .map(call -> new Violation(
                        errorLevel(),
                        errorType(),
                        code(),
                        name(),
                        sourceFile.path(),
                        call.getBegin().map(position -> position.line).orElse(0),
                        "Duration.ofDays(" + call.getArgument(0) + ") в роли года: длина года не постоянна,"
                                + " из-за високосных лет результат сдвинется на день; используйте"
                                + " plusYears(...) или Period.ofYears(...)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.DATE_AND_TIME;
    }

    // 365, 365L, (365), 365 * years, years * 365
    private boolean isYearInDays(Expression expression) {
        if (expression.isEnclosedExpr()) {
            return isYearInDays(expression.asEnclosedExpr().getInner());
        }
        if (expression.isIntegerLiteralExpr()) {
            return DAYS_IN_YEAR.contains(expression.asIntegerLiteralExpr().getValue());
        }
        if (expression.isLongLiteralExpr()) {
            return DAYS_IN_YEAR.contains(expression.asLongLiteralExpr().getValue().replaceAll("[lL]$", ""));
        }
        if (expression.isBinaryExpr() && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.MULTIPLY) {
            return isYearInDays(expression.asBinaryExpr().getLeft())
                    || isYearInDays(expression.asBinaryExpr().getRight());
        }
        return false;
    }
}
