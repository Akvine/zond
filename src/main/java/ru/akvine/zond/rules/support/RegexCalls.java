package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Методы String, которые при каждом вызове компилируют регулярное выражение из своего аргумента
 */
@UtilityClass
public class RegexCalls {
    private static final Set<String> REPLACING = Set.of("replaceAll", "replaceFirst");
    private static final String MATCHES = "matches";
    private static final String SPLIT = "split";
    private static final String MATCHER = "matcher";
    // У Pattern.split(text) и Splitter.split(text) в аргументе не шаблон, а разбираемый текст
    private static final Set<String> COMPILED_TYPES = Set.of("Matcher", "Pattern", "Splitter");
    // Символы, которые в шаблоне что-то значат
    private static final Pattern REGEX_SYNTAX = Pattern.compile("[\\\\\\[\\](){}.*+?^$|]");
    private static final String SPLIT_SPECIAL = ".$|()[{^?*+\\";
    private static final char ESCAPE = '\\';

    /**
     * @return true для value.replaceAll("\\s+", " "), code.matches("[A-Z]{3}") и row.split("\\s*;\\s*"):
     * шаблон задан строкой в коде, и String компилирует его заново при каждом вызове
     */
    public boolean compilesConstantRegex(MethodCallExpr call) {
        if (call.getArguments().isEmpty() || !call.getArgument(0).isStringLiteralExpr()
                || call.getScope().isEmpty() || isOnCompiled(call)) {
            return false;
        }
        String method = call.getNameAsString();
        if (SPLIT.equals(method)) {
            return !splitsWithoutRegex(call.getArgument(0).asStringLiteralExpr().asString());
        }
        return REPLACING.contains(method) || MATCHES.equals(method) && call.getArguments().size() == 1;
    }

    /**
     * @return true, если в шаблоне замены нет ни одного специального символа: replaceAll(" ", "") ищет обычный
     * текст, и ему хватит replace(...)
     */
    public boolean replacesPlainText(MethodCallExpr call) {
        return REPLACING.contains(call.getNameAsString())
                && !REGEX_SYNTAX.matcher(call.getArgument(0).asStringLiteralExpr().asString()).find();
    }

    // split(",") и split("\\|"): разделитель из одного обычного символа или экранированного знака String
    // ищет сам, без шаблона
    private boolean splitsWithoutRegex(String regex) {
        if (regex.length() == 1) {
            return SPLIT_SPECIAL.indexOf(regex.charAt(0)) < 0;
        }
        return regex.length() == 2 && regex.charAt(0) == ESCAPE && !isAsciiLetterOrDigit(regex.charAt(1));
    }

    private boolean isAsciiLetterOrDigit(char symbol) {
        return symbol >= '0' && symbol <= '9' || symbol >= 'a' && symbol <= 'z' || symbol >= 'A' && symbol <= 'Z';
    }

    // SPACES.matcher(text).replaceAll(" "): шаблон уже скомпилирован, а строка в аргументе - замена
    private boolean isOnCompiled(MethodCallExpr call) {
        Expression scope = Nodes.unwrap(call.getScope().orElseThrow());
        return scope.isMethodCallExpr() && MATCHER.equals(scope.asMethodCallExpr().getNameAsString())
                || LocalTypes.typeOf(scope).filter(COMPILED_TYPES::contains).isPresent();
    }
}
