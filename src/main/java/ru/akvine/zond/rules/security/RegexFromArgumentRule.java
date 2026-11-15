package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code value.replaceAll(arguments[0], arguments[1])}: шаблон не записан в коде, а приходит в метод вместе с
 * данными - элементом массива аргументов, полем объекта с настройками правила. Проследить такое значение до
 * запроса удается не всегда: по дороге оно проходит через преобразования объектов и коллекции. Но если шаблоны
 * задают пользователи, движок java.util.regex становится уязвимым местом: он ищет с возвратами, на выражении
 * вроде (a+)+$ одна строка занимает поток на часы, и прервать такой поиск нельзя.
 */
@Component
public class RegexFromArgumentRule extends AbstractTaintRule {
    private static final String PATTERN = "Pattern";
    private static final String COMPILE = "compile";
    private static final String MATCHES = "matches";
    private static final String SPLIT = "split";
    private static final String GET = "get";
    private static final Set<String> STRING_REGEX_METHODS = Set.of("replaceAll", "replaceFirst", SPLIT);
    // pattern.split(text), matcher.matches(): в аргументе не шаблон
    private static final Set<String> COMPILED_TYPES = Set.of("Matcher", "Pattern", "Splitter");
    private static final List<String> COMPILED_NAMES = List.of("pattern", "splitter", "matcher", "regex");
    // rule.getPattern(), column.getMask(): объект хранит выражение. Произвольное поле шаблоном не считается
    private static final List<String> REGEX_FIELDS = List.of("regex", "regexp", "pattern", "mask", "expression");

    @Override
    public String code() {
        return RuleCodes.REGEX_FROM_ARGUMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет регулярные выражения, шаблон которых приходит в метод с данными - элементом"
                + " массива аргументов или полем объекта - и выполняется движком с возвратами";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        if (TestPaths.isTest(sourceFile.path())) {
            return violations;
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!takesRegex(call)) {
                continue;
            }
            Expression regex = Nodes.unwrap(call.getArgument(0));
            Optional<Origin> origin = originOf(regex);
            // О шаблоне, который прослежен до запроса, сообщает отдельное правило
            if (origin.isEmpty() || taint.findSource(regex).isPresent()) {
                taint.takeConfidence();
                continue;
            }
            violations.add(violation(sourceFile, call,
                    "Шаблон регулярного выражения '" + regex + "' не задан в коде, а приходит с данными ("
                            + origin.get().text() + "). Его выполняет движок java.util.regex с возвратами: на"
                            + " выражении вроде (a+)+$ одна строка занимает поток на часы (ReDoS), и прервать поиск"
                            + " нельзя. Если значение - обычный текст, экранируйте его через Pattern.quote(...);"
                            + " если выражения задают пользователи, выполняйте их движком без возвратов (RE2/J)")
                    .withConfidence(origin.get().confidence()));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private record Origin(String text, Confidence confidence) {
    }

    private Optional<Origin> originOf(Expression regex) {
        // arguments[0]
        if (regex.isArrayAccessExpr()) {
            Expression array = Nodes.unwrap(regex.asArrayAccessExpr().getName());
            return isMethodParameter(array)
                    ? Optional.of(new Origin("элемент параметра '" + array + "'", Confidence.PROBABLE))
                    : Optional.empty();
        }
        if (!regex.isMethodCallExpr() || regex.asMethodCallExpr().getScope().isEmpty()) {
            return Optional.empty();
        }
        MethodCallExpr call = regex.asMethodCallExpr();
        Expression owner = Nodes.unwrap(call.getScope().get());
        // arguments.get(0)
        if (GET.equals(call.getNameAsString()) && call.getArguments().size() == 1 && isMethodParameter(owner)) {
            return Optional.of(new Origin("элемент параметра '" + owner + "'", Confidence.PROBABLE));
        }
        // rule.getPattern(), column.regex()
        String field = call.getNameAsString().toLowerCase();
        boolean storesRegex = call.getArguments().isEmpty() && REGEX_FIELDS.stream().anyMatch(field::contains);
        return storesRegex && isDataObject(owner)
                ? Optional.of(new Origin("поле объекта '" + owner + "'", Confidence.SUSPICION))
                : Optional.empty();
    }

    private boolean isMethodParameter(Expression expression) {
        return expression.isNameExpr() && LocalTypes.findDeclaration(expression)
                .filter(declaration -> declaration instanceof Parameter parameter
                        && parameter.getParentNode().filter(MethodDeclaration.class::isInstance).isPresent())
                .isPresent();
    }

    // Параметр метода или лямбды либо переменная цикла for-each: объект пришел снаружи, а не создан здесь
    private boolean isDataObject(Expression expression) {
        if (!expression.isNameExpr()) {
            return false;
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(expression);
        if (declaration.isEmpty() || !LocalTypes.isLocal(expression, expression.asNameExpr().getNameAsString())) {
            return false;
        }
        if (declaration.get() instanceof Parameter) {
            return true;
        }
        return declaration.get() instanceof VariableDeclarator variable && variable.getParentNode()
                .flatMap(Node::getParentNode)
                .filter(ForEachStmt.class::isInstance)
                .isPresent();
    }

    private boolean takesRegex(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (call.getArguments().isEmpty() || call.getScope().isEmpty()) {
            return false;
        }
        if (MethodCalls.isCallOn(call, PATTERN, COMPILE) || MethodCalls.isCallOn(call, PATTERN, MATCHES)) {
            return true;
        }
        boolean onString = STRING_REGEX_METHODS.contains(method)
                || MATCHES.equals(method) && call.getArguments().size() == 1;
        return onString && !isOnCompiled(call.getScope().get());
    }

    private boolean isOnCompiled(Expression scope) {
        String name = MethodCalls.receiverName(scope).toLowerCase();
        return LocalTypes.typeOf(scope).filter(COMPILED_TYPES::contains).isPresent()
                || COMPILED_NAMES.stream().anyMatch(name::contains);
    }
}
