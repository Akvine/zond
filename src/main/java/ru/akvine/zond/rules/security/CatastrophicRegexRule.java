package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pattern.compile("^(\\w+\\s?)+$"): повтор внутри повтора, и одну и ту же строку можно разбить на части
 * множеством способов. На строке, которая почти подходит ("aaaaaaaaaaaaaaaaaaaaaaaa!"), движок перебирает их все -
 * время растет экспоненциально, и один присланный запрос занимает поток на минуты (ReDoS)
 */
@Component
public class CatastrophicRegexRule extends AbstractRule {
    private static final Set<String> PATTERN_METHODS = Set.of("compile", "matches");
    private static final String PATTERN = "Pattern";
    private static final Set<String> STRING_METHODS = Set.of("matches", "replaceAll", "replaceFirst", "split");
    // Ищут подстроку, а не сверяют строку целиком
    private static final Set<String> SEARCH_METHODS = Set.of("replaceAll", "replaceFirst", "split");
    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of("Pattern", "Email");
    private static final String REGEXP = "regexp";

    @Override
    public String code() {
        return RuleCodes.CATASTROPHIC_REGEX_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет регулярные выражения с вложенным повтором вида (a+)+, на которых проверка"
                + " строки растягивается на минуты (ReDoS)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Predicate<String> catastrophic = SEARCH_METHODS.contains(call.getNameAsString())
                    ? RegexShape::isCatastrophicOnSearch
                    : RegexShape::isCatastrophic;
            regexOf(call).filter(catastrophic).filter(regex -> !TestClasses.isInside(call))
                    .ifPresent(regex -> violations.add(violation(sourceFile, call, message(regex))));
        }
        for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
            if (!VALIDATION_ANNOTATIONS.contains(annotation.getName().getIdentifier())) {
                continue;
            }
            annotation.findAll(MemberValuePair.class).stream()
                    .filter(pair -> REGEXP.equals(pair.getNameAsString()))
                    .flatMap(pair -> literal(pair.getValue()).stream())
                    .filter(RegexShape::isCatastrophic)
                    .findFirst()
                    .ifPresent(regex -> violations.add(violation(sourceFile, annotation, message(regex))));
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

    private String message(String regex) {
        return "Регулярное выражение \"" + regex + "\" содержит повтор внутри повтора: строку, которая почти"
                + " подходит, движок перебирает экспоненциально долго, и один присланный запрос займет поток на"
                + " минуты (ReDoS); уберите вложенный повтор, сделайте части однозначными либо используйте"
                + " притяжательный квантификатор (++, *+) или атомарную группу (?>...)";
    }

    // Pattern.compile(EMAIL), value.matches("..."), Pattern.matches("...", value)
    private Optional<String> regexOf(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        String method = call.getNameAsString();
        boolean onPattern = PATTERN_METHODS.contains(method)
                && call.getScope().filter(scope -> PATTERN.equals(scope.toString())).isPresent();
        boolean onString = STRING_METHODS.contains(method) && call.getScope()
                .filter(scope -> !(scope.isNameExpr() && Character.isUpperCase(scope.asNameExpr().getNameAsString().charAt(0))))
                .isPresent();
        return onPattern || onString ? literal(call.getArgument(0)) : Optional.empty();
    }

    // "^(\\w+)+$", "^(" + WORD + ")+$" из литералов, константа PHONE_REGEX с таким значением
    private Optional<String> literal(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isStringLiteralExpr()) {
            return Optional.of(value.asStringLiteralExpr().asString());
        }
        if (value instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = literal(binary.getLeft());
            Optional<String> right = literal(binary.getRight());
            return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
        }
        if (value.isNameExpr()) {
            Optional<Node> declaration = LocalTypes.findDeclaration(value);
            return declaration.filter(node -> node instanceof VariableDeclarator)
                    .flatMap(node -> ((VariableDeclarator) node).getInitializer())
                    .filter(initializer -> initializer != value)
                    .flatMap(this::literal);
        }
        return Optional.empty();
    }
}
