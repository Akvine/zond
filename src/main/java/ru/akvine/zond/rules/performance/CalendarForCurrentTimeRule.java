package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code Calendar.getInstance().getTime()} и {@code Calendar.getInstance().getTimeInMillis()}: ради текущего
 * момента создается календарь - с часовым поясом, локалью и расчетом всех полей даты. То же самое отдают
 * new Date() и System.currentTimeMillis() без лишнего объекта.
 */
@Component
public class CalendarForCurrentTimeRule extends AbstractRule {
    private static final String CALENDAR = "Calendar";
    private static final String GET_INSTANCE = "getInstance";
    private static final String GREGORIAN_CALENDAR = "GregorianCalendar";
    // Чем заменить
    private static final Map<String, String> REPLACEMENTS = Map.of(
            "getTime", "new Date() или Instant.now()",
            "getTimeInMillis", "System.currentTimeMillis()");

    @Override
    public String code() {
        return RuleCodes.CALENDAR_FOR_CURRENT_TIME_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Calendar.getInstance().getTime() и getTimeInMillis(): календарь создается"
                + " только ради текущего момента времени";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            String replacement = REPLACEMENTS.get(call.getNameAsString());
            if (replacement == null || !call.getArguments().isEmpty() || TestClasses.isInside(call)
                    || call.getScope().filter(this::isNewCalendar).isEmpty()) {
                continue;
            }
            violations.add(violation(sourceFile, call,
                    "'" + Nodes.text(call) + "' создает календарь ради текущего момента: внутри определяются часовой"
                            + " пояс и локаль и рассчитываются все поля даты, а нужно одно число; используйте "
                            + replacement)
                    .withLevel(Loops.isRepeated(call) ? ErrorLevel.MINOR : ErrorLevel.INFO));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Calendar.getInstance(), Calendar.getInstance(zone), new GregorianCalendar()
    private boolean isNewCalendar(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isObjectCreationExpr()) {
            return GREGORIAN_CALENDAR.equals(value.asObjectCreationExpr().getType().getNameAsString())
                    && value.asObjectCreationExpr().getArguments().isEmpty();
        }
        return value.isMethodCallExpr() && MethodCalls.isCallOn(value.asMethodCallExpr(), CALENDAR, GET_INSTANCE);
    }
}
