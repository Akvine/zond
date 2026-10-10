package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Taint;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class LogInjectionRule extends AbstractTaintRule {
    private static final Set<String> LINE_FREE_TYPES = Set.of(
            "int", "long", "short", "byte", "double", "float", "boolean", "char", "Integer", "Long", "Short", "Byte",
            "Double", "Float", "Boolean", "Character", "BigDecimal", "BigInteger", "UUID", "LocalDate", "LocalDateTime",
            "LocalTime", "Instant", "OffsetDateTime", "ZonedDateTime", "Date", "Duration", "Timestamp");
    private static final Set<String> TEXT_TYPES = Set.of("String", "CharSequence", "StringBuilder", "Object");

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
            findRawInput(call, taint).ifPresent(input -> {
                Violation violation = violation(sourceFile, call,
                        "В лог пишутся данные клиента '" + input.source() + "' как есть: переводом строки в значении"
                                + " можно подделать записи лога; уберите из значения символы \\r и \\n");
                // В лог уходит объект целиком: попадет ли в запись его строковое поле, зависит от toString()
                violations.add(input.text() ? violation : violation.withConfidence(Confidence.SUSPICION));
            });
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

    /**
     * @param text значение - строка либо его тип неизвестен; false - объект, который в лог попадет через toString()
     */
    private record Input(String source, boolean text) {
    }

    private Optional<Input> findRawInput(MethodCallExpr call, Taint taint) {
        for (Expression argument : call.getArguments()) {
            Optional<String> type = LocalTypes.typeOf(argument);
            // Число, дата, идентификатор перевод строки содержать не могут
            if (CLEANED.matcher(argument.toString()).matches() || type.filter(LINE_FREE_TYPES::contains).isPresent()) {
                continue;
            }
            // Подделать строку лога пытается тот, кто шлет данные сам; ответ внешнего сервиса в логе - обычное дело
            boolean text = type.filter(name -> !TEXT_TYPES.contains(name)).isEmpty();
            Optional<Input> source = taint.findSource(argument)
                    .filter(Taint.Source::isDirectInput)
                    .map(found -> new Input(found.toString(), text));
            if (source.isPresent()) {
                return source;
            }
        }
        return Optional.empty();
    }
}
