package ru.akvine.zond.rules.datetime;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.List;

@Component
public class CheckLocalDateTimeNowRule implements Rule {
    private static final String LOCAL_DATE_TIME = "LocalDateTime";
    private static final String NOW = "now";

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_LOCAL_DATE_TIME_NOW_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет LocalDateTime.now() для временных меток вместо типов с часовым поясом";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // now(zone) и now(clock) не трогаем: там пояс выбран явно
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> MethodCalls.isCallOn(call, LOCAL_DATE_TIME, NOW))
                .filter(call -> call.getArguments().isEmpty())
                .map(call -> new Violation(
                        errorLevel(),
                        errorType(),
                        code(),
                        name(),
                        sourceFile.path(),
                        call.getBegin().map(position -> position.line).orElse(0),
                        "LocalDateTime.now() для временной метки: значение берется в часовом поясе сервера"
                                + " и не хранит его, один и тот же момент на разных серверах даст разное время;"
                                + " используйте Instant.now(), ZonedDateTime.now() или OffsetDateTime.now()"))
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
}
