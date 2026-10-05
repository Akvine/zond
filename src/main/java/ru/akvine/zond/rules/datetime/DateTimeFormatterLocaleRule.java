package ru.akvine.zond.rules.datetime;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DateTimeFormatterLocaleRule implements Rule {
    private static final String DATE_TIME_FORMATTER = "DateTimeFormatter";
    private static final String OF_PATTERN = "ofPattern";
    private static final String LOCALE = "Locale";
    private static final Set<String> LOCALE_METHODS = Set.of("withLocale", "localizedBy");

    // Текст в одинарных кавычках выводится как есть и к полям шаблона не относится
    private static final Pattern QUOTED_TEXT = Pattern.compile("'[^']*'");

    // Поля, которые выводятся словами и зависят от локали: название месяца, день недели, AM/PM, эра
    private static final Pattern TEXT_FIELDS = Pattern.compile("M{3,}|L{3,}|E|a|G");

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.DATE_TIME_FORMATTER_LOCALE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет DateTimeFormatter с неподходящей локалью либо без локали при выводе названий";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!MethodCalls.isCallOn(call, DATE_TIME_FORMATTER, OF_PATTERN)
                    || call.getArguments().isEmpty()
                    || !call.getArgument(0).isStringLiteralExpr()) {
                continue;
            }

            // Без названий месяцев и дней недели локаль на результат не влияет
            String pattern = call.getArgument(0).asStringLiteralExpr().asString();
            if (!TEXT_FIELDS.matcher(QUOTED_TEXT.matcher(pattern).replaceAll("")).find()) {
                continue;
            }

            Optional<Expression> locale = findLocale(call);
            if (locale.isEmpty()) {
                violations.add(violation(sourceFile, call,
                        "DateTimeFormatter.ofPattern(\"" + pattern + "\") без локали: названия месяцев и дней"
                                + " недели будут на языке сервера; укажите локаль явно"));
            } else if (hasCyrillic(pattern) && isLocaleConstant(locale.get())) {
                violations.add(violation(sourceFile, call,
                        "DateTimeFormatter.ofPattern(\"" + pattern + "\") с локалью " + locale.get()
                                + " при русском тексте в шаблоне: названия месяцев и дней недели будут не на"
                                + " русском; используйте Locale.forLanguageTag(\"ru\")"));
            }
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

    // ofPattern(pattern, locale) либо ofPattern(pattern).withLocale(locale)
    private Optional<Expression> findLocale(MethodCallExpr call) {
        if (call.getArguments().size() > 1) {
            return Optional.of(call.getArgument(1));
        }

        Node parent = call.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr chained
                && chained.getScope().filter(scope -> scope == call).isPresent()
                && LOCALE_METHODS.contains(chained.getNameAsString())
                && chained.getArguments().size() == 1) {
            return Optional.of(chained.getArgument(0));
        }
        return Optional.empty();
    }

    // Locale.US, Locale.ENGLISH и т.п.: константы для русской локали в классе Locale нет
    private boolean isLocaleConstant(Expression locale) {
        return locale.isFieldAccessExpr() && MethodCalls.isType(locale.asFieldAccessExpr().getScope(), LOCALE);
    }

    private boolean hasCyrillic(String text) {
        return text.chars().anyMatch(symbol -> Character.UnicodeBlock.of(symbol) == Character.UnicodeBlock.CYRILLIC);
    }

    private Violation violation(SourceFile sourceFile, MethodCallExpr call, String message) {
        return new Violation(
                errorLevel(),
                errorType(),
                code(),
                name(),
                sourceFile.path(),
                call.getBegin().map(position -> position.line).orElse(0),
                message);
    }
}
