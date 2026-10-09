package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.rules.files.ConfigKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Свойства, которые код читает через {@code @Value("${...}")}, и то, заданы ли они в файлах настроек.
 * Этим пользуются все правила, которые сверяют код с настройками, - чтобы понимать запись одинаково.
 */
@UtilityClass
public class ValuePlaceholders {
    private static final String VALUE = "Value";
    private static final String VALUE_MEMBER = "value";

    // ${app.name} и ${app.name:значение по умолчанию}
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(:[^}]*)?}");

    // DB_HOST, JAVA_HOME: переменная окружения, а не свойство из файла
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /**
     * @param key        ключ свойства
     * @param hasDefault после ключа через двоеточие задано значение по умолчанию
     */
    public record Placeholder(String key, boolean hasDefault) {
    }

    public boolean isValue(AnnotationExpr annotation) {
        return VALUE.equals(annotation.getName().getIdentifier());
    }

    /**
     * @return текст аннотации в любой записи: {@code @Value("...")} и {@code @Value(value = "...")};
     * если он собран из констант, которых в коде не видно, - его литеральные части
     */
    public String textOf(AnnotationExpr annotation) {
        Optional<Expression> value = annotation.isSingleMemberAnnotationExpr()
                ? Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                : Queries.member(annotation, VALUE_MEMBER);
        return value.flatMap(StringLiterals::textOf)
                .orElseGet(() -> annotation.findAll(StringLiteralExpr.class).stream()
                        .map(StringLiteralExpr::asString)
                        .collect(Collectors.joining()));
    }

    /**
     * @return все подстановки ${...} из аннотации, в порядке следования
     */
    public List<Placeholder> of(AnnotationExpr annotation) {
        List<Placeholder> placeholders = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(textOf(annotation));
        while (matcher.find()) {
            placeholders.add(new Placeholder(matcher.group(1).trim(), matcher.group(2) != null));
        }
        return placeholders;
    }

    public boolean isEnvironmentVariable(String key) {
        return ENVIRONMENT_VARIABLE.matcher(key).matches();
    }

    /**
     * @return true, если свойство задано в файле само либо как набор вложенных: app.items[0], app.limits.max
     */
    public boolean isDefinedIn(String key, ConfigFile file) {
        String expected = ConfigKeys.normalize(key);
        return file.properties().stream()
                .map(property -> ConfigKeys.normalize(property.key()))
                .anyMatch(defined -> defined.equals(expected)
                        || defined.startsWith(expected + ".") || defined.startsWith(expected + "["));
    }
}
