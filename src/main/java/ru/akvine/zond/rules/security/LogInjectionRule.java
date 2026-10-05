package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Taint;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class LogInjectionRule extends AbstractTaintRule {
    // Значение уже очищено: value.replaceAll("[\r\n]", "_"), sanitize(value), encode(value)
    private static final Pattern CLEANED = Pattern.compile(".*(replace|strip|sanitize|encode|escape|clean).*");

    @Override
    public String code() {
        return RuleCodes.LOG_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись данных запроса в лог без очистки переводов строк";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!Loggers.isLogCall(call) || TestClasses.isInside(call)) {
                continue;
            }
            findRawInput(call, taint).ifPresent(source -> violations.add(violation(sourceFile, call,
                    "В лог пишутся данные клиента '" + source + "' как есть: переводом строки в значении"
                            + " можно подделать записи лога; уберите из значения символы \\r и \\n")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private Optional<String> findRawInput(MethodCallExpr call, Taint taint) {
        for (Expression argument : call.getArguments()) {
            if (CLEANED.matcher(argument.toString()).matches()) {
                continue;
            }
            // Подделать строку лога пытается тот, кто шлет данные сам; ответ внешнего сервиса в логе - обычное дело
            Optional<String> source = taint.findSource(argument)
                    .filter(Taint.Source::isDirectInput)
                    .map(Taint.Source::toString);
            if (source.isPresent()) {
                return source;
            }
        }
        return Optional.empty();
    }
}
