package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;

@Component
public class CheckCurrentTimeMillisForDurationRule extends AbstractRule {
    private static final String SYSTEM = "System";
    private static final String CURRENT_TIME_MILLIS = "currentTimeMillis";

    @Override
    public String code() {
        return RuleCodes.CHECK_CURRENT_TIME_MILLIS_FOR_DURATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет замер длительности через System.currentTimeMillis()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Разность двух отсчетов времени - это длительность
        return sourceFile.unit().findAll(BinaryExpr.class).stream()
                .filter(difference -> difference.getOperator() == BinaryExpr.Operator.MINUS)
                .filter(difference -> isWallClock(difference.getLeft()) && isWallClock(difference.getRight()))
                .map(difference -> violation(sourceFile, difference,
                        "'" + difference + "': currentTimeMillis() - время по часам системы, которые могут"
                                + " быть переведены, пока идет замер, и длительность выйдет неверной или"
                                + " отрицательной; для интервалов используйте System.nanoTime()"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Сам вызов либо переменная, в которую он сохранен: long start = System.currentTimeMillis()
    private boolean isWallClock(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        Expression source = LocalTypes.findInitializer(value).map(Nodes::unwrap).orElse(value);
        return source.isMethodCallExpr()
                && MethodCalls.isCallOn(source.asMethodCallExpr(), SYSTEM, CURRENT_TIME_MILLIS);
    }
}
