package ru.akvine.zond.rules.datetime;

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
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class InstantUnsupportedUnitRule extends AbstractRule {
    private static final String INSTANT = "Instant";
    private static final Set<String> ARITHMETIC_METHODS = Set.of("plus", "minus", "until", "truncatedTo");

    // Instant - точка на временной оси без календаря: единицы длиннее суток он не поддерживает
    private static final Set<String> UNSUPPORTED_UNITS =
            Set.of("WEEKS", "MONTHS", "YEARS", "DECADES", "CENTURIES", "MILLENNIA", "ERAS", "FOREVER");

    @Override
    public String code() {
        return RuleCodes.INSTANT_UNSUPPORTED_UNIT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет арифметику над Instant в неделях, месяцах и годах";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!ARITHMETIC_METHODS.contains(call.getNameAsString())
                    || call.getScope().filter(this::isInstant).isEmpty()) {
                continue;
            }
            findUnsupportedUnit(call).ifPresent(unit -> violations.add(violation(sourceFile, call,
                    "'" + call + "': Instant не поддерживает единицу " + unit + ", вызов закончится"
                            + " UnsupportedTemporalTypeException; переведите момент в ZonedDateTime с нужным поясом"
                            + " и считайте там")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.DATE_AND_TIME;
    }

    // Переменная типа Instant либо Instant.now()
    private boolean isInstant(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return value.asMethodCallExpr().getScope().filter(type -> MethodCalls.isType(type, INSTANT)).isPresent();
        }
        return LocalTypes.typeOf(value).filter(INSTANT::equals).isPresent();
    }

    // ChronoUnit.MONTHS либо MONTHS через статический импорт
    private Optional<String> findUnsupportedUnit(MethodCallExpr call) {
        return call.getArguments().stream()
                .map(Nodes::unwrap)
                .map(argument -> argument.isFieldAccessExpr()
                        ? argument.asFieldAccessExpr().getNameAsString()
                        : argument.toString())
                .filter(UNSUPPORTED_UNITS::contains)
                .findFirst();
    }
}
