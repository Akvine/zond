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
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FormatArgumentsMismatchRule extends AbstractRule {
    private static final String STRING = "String";
    private static final String FORMAT = "format";
    private static final String FORMATTED = "formatted";
    private static final String LOCALE = "Locale";
    private static final String ARRAY_SUFFIX = "[]";

    // %s, %5d, %-10.2f, %tY; группа 1 - номер аргумента (%2$s), группа 2 - флаги, группа 3 - вид подстановки
    private static final Pattern SPECIFIER =
            Pattern.compile("%(\\d+\\$)?([-#+ 0,(<]*)\\d*(?:\\.\\d+)?[tT]?([a-zA-Z%])");
    private static final String NOT_ARGUMENTS = "%n";
    private static final String PREVIOUS_ARGUMENT = "<";

    @Override
    public String code() {
        return RuleCodes.FORMAT_ARGUMENTS_MISMATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет String.format, где число аргументов не совпадает с числом подстановок";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<Integer> formatIndex = formatIndex(call);
            Optional<String> format = formatIndex.flatMap(index -> formatText(call, index));
            Optional<Integer> expected = format.flatMap(this::countArguments);
            if (expected.isEmpty()) {
                continue;
            }

            List<Expression> arguments = call.getArguments().subList(formatIndex.get() + 1, call.getArguments().size());
            // Единственный аргумент-массив раскрывается в несколько значений
            boolean isArray = arguments.size() == 1
                    && LocalTypes.typeOf(arguments.get(0)).filter(type -> type.endsWith(ARRAY_SUFFIX)).isPresent();
            if (isArray || expected.get() == arguments.size()) {
                continue;
            }
            violations.add(violation(sourceFile, call,
                    "В шаблоне " + expected.get() + " подстановок, а аргументов " + arguments.size() + ": "
                            + (expected.get() > arguments.size()
                            ? "будет MissingFormatArgumentException"
                            : "лишние аргументы в строку не попадут")
                            + "; приведите их в соответствие"));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    /**
     * @return номер аргумента с шаблоном: 0 или 1 у String.format (первым может идти Locale),
     * -1 у "шаблон".formatted(...), где шаблон - сама строка
     */
    private Optional<Integer> formatIndex(MethodCallExpr call) {
        if (FORMATTED.equals(call.getNameAsString()) && call.getScope().isPresent()) {
            return Optional.of(-1);
        }
        if (!MethodCalls.isCallOn(call, STRING, FORMAT) || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        // String.format(Locale.ROOT, "...", args): шаблон идет вторым
        boolean startsWithLocale = call.getArguments().size() > 1
                && (call.getArgument(0).toString().contains(LOCALE)
                || LocalTypes.typeOf(call.getArgument(0)).filter(LOCALE::equals).isPresent());
        return Optional.of(startsWithLocale ? 1 : 0);
    }

    // Шаблон записан строкой прямо в вызове либо константой этого же класса; иначе его текст неизвестен
    private Optional<String> formatText(MethodCallExpr call, int index) {
        Expression format = index < 0 ? call.getScope().get() : call.getArgument(index);
        return StringLiterals.textOf(format)
                .or(() -> LocalTypes.findInitializer(format).flatMap(StringLiterals::textOf));
    }

    /**
     * @return сколько аргументов нужно шаблону; пусто, если в нем есть обращения по номеру (%2$s) -
     * тогда число подстановок и число аргументов не связаны
     */
    private Optional<Integer> countArguments(String format) {
        int count = 0;
        Matcher matcher = SPECIFIER.matcher(format);
        while (matcher.find()) {
            // %% - знак процента, %n - перевод строки
            if ("%".equals(matcher.group(3)) || NOT_ARGUMENTS.equals(matcher.group())) {
                continue;
            }
            if (matcher.group(1) != null || matcher.group(2).contains(PREVIOUS_ARGUMENT)) {
                return Optional.empty();
            }
            count++;
        }
        return Optional.of(count);
    }
}
