package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Значения членов аннотации: {@code @Qualifier("origin")}, {@code @Profile({"dev", "local"})},
 * {@code @ConditionalOnProperty(name = BotConstants.TYPE, havingValue = "webhook")}. Значение бывает записано
 * константой - тогда берется ее текст, а если его не узнать, само выражение: одна и та же константа в двух
 * местах означает одно и то же значение
 */
@UtilityClass
public class AnnotationValues {
    private final static String VALUE = "value";
    // Константа, значение которой - другая константа: дальше пары шагов не идем
    private final static int MAX_DEPTH = 3;

    /**
     * @param members имена членов; value относится и к записи без имени: @Profile("dev")
     * @return выражения названных членов; массив раскрывается в элементы
     */
    public List<Expression> expressions(AnnotationExpr annotation, Set<String> members) {
        List<Expression> values = new ArrayList<>();
        if (annotation.isSingleMemberAnnotationExpr() && members.contains(VALUE)) {
            values.add(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (annotation.isNormalAnnotationExpr()) {
            annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> members.contains(pair.getNameAsString()))
                    .forEach(pair -> values.add(pair.getValue()));
        }
        List<Expression> flat = new ArrayList<>();
        for (Expression value : values) {
            if (value.isArrayInitializerExpr()) {
                flat.addAll(value.asArrayInitializerExpr().getValues());
            } else {
                flat.add(value);
            }
        }
        return flat;
    }

    /**
     * @return текст значения: строка либо константа проекта, которой присвоена строка
     */
    public Optional<String> text(Expression value, ProjectClasses classes) {
        return text(value, classes, 0);
    }

    /**
     * @return значения для сравнения между собой: известный текст, а вместо неизвестного - само выражение в
     * угловых скобках
     */
    public List<String> keys(AnnotationExpr annotation, Set<String> members, ProjectClasses classes) {
        return expressions(annotation, members).stream()
                .map(value -> text(value, classes).orElse("<" + value + ">"))
                .toList();
    }

    private Optional<String> text(Expression expression, ProjectClasses classes, int depth) {
        Expression value = Nodes.unwrap(expression);
        Optional<String> literal = StringLiterals.textOf(value);
        if (literal.isPresent() || depth >= MAX_DEPTH) {
            return literal;
        }
        Optional<VariableDeclarator> constant = Optional.empty();
        if (value.isNameExpr()) {
            constant = LocalTypes.findField(value, value.asNameExpr().getNameAsString());
        } else if (value.isFieldAccessExpr()) {
            // ProxyConstants.ORIGIN_BEAN_NAME, a.b.Constants.NAME: владелец - последнее имя перед константой
            String scope = value.asFieldAccessExpr().getScope().toString();
            String owner = scope.substring(scope.lastIndexOf('.') + 1);
            String name = value.asFieldAccessExpr().getNameAsString();
            constant = classes.find(owner)
                    .flatMap(type -> type.getFieldByName(name))
                    .flatMap(field -> field.getVariables().stream()
                            .filter(variable -> variable.getNameAsString().equals(name))
                            .findFirst());
        }
        return constant.flatMap(VariableDeclarator::getInitializer)
                .flatMap(initializer -> text(initializer, classes, depth + 1));
    }
}
