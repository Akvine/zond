package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckBigDecimalDivideRule extends AbstractRule {
    private static final String BIG_DECIMAL = "BigDecimal";
    private static final String DIVIDE = "divide";

    @Override
    public String code() {
        return RuleCodes.CHECK_BIG_DECIMAL_DIVIDE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет BigDecimal.divide() без указания округления";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // divide(divisor) с одним аргументом; варианты с масштабом, RoundingMode или MathContext безопасны
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> DIVIDE.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getScope().filter(this::isBigDecimal).isPresent()
                        || isBigDecimal(call.getArgument(0)))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' без округления: если частное - бесконечная дробь (1/3), будет"
                                + " ArithmeticException \"Non-terminating decimal expansion\"; укажите масштаб"
                                + " и RoundingMode: divide(divisor, 2, RoundingMode.HALF_UP)"))
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

    // Переменная типа BigDecimal, new BigDecimal(...), BigDecimal.ONE, BigDecimal.valueOf(...)
    private boolean isBigDecimal(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isFieldAccessExpr() && MethodCalls.isType(value.asFieldAccessExpr().getScope(), BIG_DECIMAL)) {
            return true;
        }
        if (value.isMethodCallExpr()
                && value.asMethodCallExpr().getScope()
                .filter(scope -> MethodCalls.isType(scope, BIG_DECIMAL))
                .isPresent()) {
            return true;
        }
        return LocalTypes.typeOf(value).filter(BIG_DECIMAL::equals).isPresent();
    }
}
