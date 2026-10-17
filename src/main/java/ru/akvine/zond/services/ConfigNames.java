package ru.akvine.zond.services;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Файлы настроек, которые проект называет сам, а не application.yml: имя из spring.config.name
 * (thingsboard.yml) и файлы из @PropertySource. Без них о свойствах этих файлов было бы сказано, что их нет
 */
@UtilityClass
public class ConfigNames {
    private static final String CONFIG_NAME_KEY = "spring.config.name";
    private static final String CONFIG_NAME_ASSIGNMENT = CONFIG_NAME_KEY + "=";
    private static final String SET_PROPERTY = "setProperty";
    private static final Set<String> PROPERTY_SOURCES = Set.of("PropertySource", "PropertySources");
    private static final String PLACEHOLDER = "${";
    // "--spring.config.name=" + NAME: константы раскрываются на столько шагов
    private static final int MAX_DEPTH = 3;

    /**
     * @return имена без расширения из spring.config.name (thingsboard) и полные имена файлов из @PropertySource
     * (recaptcha.properties)
     */
    public Set<String> of(List<SourceFile> sources) {
        Set<String> names = new HashSet<>();
        for (SourceFile source : sources) {
            // DEFAULT_ARGUMENT = "--spring.config.name=" + "thingsboard"; .properties("spring.config.name=app")
            for (Expression expression : source.unit().findAll(Expression.class, ConfigNames::isWholeText)) {
                textOf(expression, MAX_DEPTH)
                        .filter(text -> text.contains(CONFIG_NAME_ASSIGNMENT))
                        .map(text -> text.substring(text.indexOf(CONFIG_NAME_ASSIGNMENT) + CONFIG_NAME_ASSIGNMENT.length()))
                        .ifPresent(value -> addConfigNames(value, names));
            }
            // System.setProperty("spring.config.name", "thingsboard")
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                if (SET_PROPERTY.equals(call.getNameAsString()) && call.getArguments().size() == 2
                        && StringLiterals.textOf(call.getArgument(0)).filter(CONFIG_NAME_KEY::equals).isPresent()) {
                    textOf(call.getArgument(1), MAX_DEPTH).ifPresent(value -> addConfigNames(value, names));
                }
            }
            // @PropertySource("classpath:recaptcha.properties")
            for (AnnotationExpr annotation : source.unit().findAll(AnnotationExpr.class)) {
                if (PROPERTY_SOURCES.contains(annotation.getName().getIdentifier())) {
                    annotation.findAll(StringLiteralExpr.class).stream()
                            .map(StringLiteralExpr::asString)
                            .filter(location -> !location.contains(PLACEHOLDER))
                            .map(ConfigNames::fileName)
                            .filter(name -> !name.isEmpty())
                            .forEach(names::add);
                }
            }
        }
        return names;
    }

    // Строка или склейка целиком, а не ее часть: "a" + b + "c" проверяется один раз
    private boolean isWholeText(Expression expression) {
        boolean text = expression.isStringLiteralExpr()
                || expression.isBinaryExpr() && expression.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS;
        return text && expression.getParentNode()
                .filter(parent -> parent instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS)
                .isEmpty();
    }

    // Текст строки, склейки строк и констант со строковым значением
    private Optional<String> textOf(Expression expression, int depth) {
        Expression value = Nodes.unwrap(expression);
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<String> left = textOf(value.asBinaryExpr().getLeft(), depth);
            Optional<String> right = textOf(value.asBinaryExpr().getRight(), depth);
            return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
        }
        if (value.isNameExpr() && depth > 0) {
            return LocalTypes.findInitializer(value).flatMap(initializer -> textOf(initializer, depth - 1));
        }
        return StringLiterals.textOf(value);
    }

    // "app,thingsboard": Spring читает настройки под каждым из имен
    private void addConfigNames(String value, Set<String> names) {
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty() && !name.contains(PLACEHOLDER))
                .forEach(names::add);
    }

    // classpath:config/recaptcha.properties -> recaptcha.properties
    private String fileName(String location) {
        String path = location.substring(location.indexOf(':') + 1);
        return path.substring(path.lastIndexOf('/') + 1).trim();
    }
}
