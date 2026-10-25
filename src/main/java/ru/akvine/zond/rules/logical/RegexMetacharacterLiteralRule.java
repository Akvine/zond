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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * split(".") и replaceAll("|", ...): метод String принимает регулярное выражение, а не строку. Точка - любой
 * символ, и split(".") всегда возвращает пустой массив; | - пустая альтернатива, и split("|") режет по каждому
 * символу; *, + и ? без того, к чему они относятся, - ошибка разбора шаблона во время работы
 */
@Component
public class RegexMetacharacterLiteralRule extends AbstractRule {
    private static final Set<String> REGEX_METHODS = Set.of("split", "replaceAll", "replaceFirst");
    private static final String STRING = "String";

    // Что выйдет из каждого символа, если записать его как регулярное выражение
    private static final Map<String, String> CONSEQUENCES = Map.of(
            ".", "точка означает любой символ: split(\".\") вернет пустой массив, replaceAll(\".\", ...) заменит"
                    + " всю строку",
            "|", "| - пустая альтернатива: совпадение будет между каждой парой символов",
            "$", "$ - конец строки: разделитель ни разу не найдется",
            "^", "^ - начало строки: разделитель ни разу не найдется",
            "*", "* без того, к чему он относится, - ошибка разбора шаблона (PatternSyntaxException)",
            "+", "+ без того, к чему он относится, - ошибка разбора шаблона (PatternSyntaxException)",
            "?", "? без того, к чему он относится, - ошибка разбора шаблона (PatternSyntaxException)",
            "(", "незакрытая скобка - ошибка разбора шаблона (PatternSyntaxException)",
            "[", "незакрытая скобка - ошибка разбора шаблона (PatternSyntaxException)",
            "\\", "одиночная обратная косая черта - ошибка разбора шаблона (PatternSyntaxException)");

    @Override
    public String code() {
        return RuleCodes.REGEX_METACHARACTER_LITERAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет split, replaceAll и replaceFirst со спецсимволом регулярного выражения"
                + " вместо строки: split(\".\"), split(\"|\")";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> REGEX_METHODS.contains(call.getNameAsString()) && !call.getArguments().isEmpty())
                .filter(this::isCalledOnString)
                .flatMap(call -> metacharacter(call.getArgument(0)).stream()
                        .map(symbol -> violation(sourceFile, call,
                                "'" + call + "': " + call.getNameAsString() + " понимает аргумент как регулярное"
                                        + " выражение, а " + CONSEQUENCES.get(symbol) + "; экранируйте символ"
                                        + " (\"\\\\" + symbol + "\") либо оберните строку в Pattern.quote(...)")))
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

    private Optional<String> metacharacter(Expression argument) {
        if (!argument.isStringLiteralExpr()) {
            return Optional.empty();
        }
        String value = argument.asStringLiteralExpr().asString();
        return CONSEQUENCES.containsKey(value) ? Optional.of(value) : Optional.empty();
    }

    // line.split(".") у строки; StringUtils.split(line, ".") из commons-lang и Splitter.on(".") понимают
    // разделитель буквально
    private boolean isCalledOnString(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        Optional<String> type = LocalTypes.typeOf(scope.get());
        if (type.isPresent()) {
            return STRING.equals(type.get());
        }
        boolean typeName = scope.get().isNameExpr()
                && Character.isUpperCase(scope.get().asNameExpr().getNameAsString().charAt(0));
        return !typeName && call.getArguments().size() <= 2;
    }
}
