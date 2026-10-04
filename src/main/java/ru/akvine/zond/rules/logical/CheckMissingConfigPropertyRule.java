package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ConfigKeys;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckMissingConfigPropertyRule extends AbstractContextRule {
    private static final String VALUE = "Value";

    // ${app.name} и ${app.name:значение по умолчанию}
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(:[^}]*)?}");

    // DB_HOST, JAVA_HOME: переменная окружения, а не свойство из файла
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Override
    public String code() {
        return RuleCodes.CHECK_MISSING_CONFIG_PROPERTY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и настройки и ищет @Value с ключом, которого нет ни в одном файле настроек";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        // Настройки в проверку не попали - судить о том, чего в них нет, нельзя
        if (context.configFiles().isEmpty()) {
            return violations;
        }
        for (SourceFile sourceFile : context.sources()) {
            for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
                if (!VALUE.equals(annotation.getName().getIdentifier()) || TestClasses.isInside(annotation)) {
                    continue;
                }
                Matcher placeholder = PLACEHOLDER.matcher(textOf(annotation).orElse(""));
                while (placeholder.find()) {
                    String key = placeholder.group(1).trim();
                    boolean hasDefault = placeholder.group(2) != null;
                    if (!hasDefault && !ENVIRONMENT_VARIABLE.matcher(key).matches() && !isDefined(key, context.configFiles())) {
                        violations.add(violation(sourceFile, annotation,
                                "Свойства '" + key + "' нет ни в одном файле настроек, а значения по умолчанию"
                                        + " у @Value нет: приложение не запустится; добавьте свойство либо"
                                        + " значение по умолчанию - ${" + key + ":...}"));
                    }
                }
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
        return ErrorType.LOGICAL;
    }

    private Optional<String> textOf(AnnotationExpr annotation) {
        Optional<Expression> value = annotation.isSingleMemberAnnotationExpr()
                ? Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                : Optional.empty();
        return value.flatMap(StringLiterals::textOf);
    }

    // Свойство задано само либо как набор вложенных: app.items[0], app.limits.max
    private boolean isDefined(String key, List<ConfigFile> configFiles) {
        String expected = ConfigKeys.normalize(key);
        return configFiles.stream()
                .flatMap(file -> file.properties().stream())
                .map(property -> ConfigKeys.normalize(property.key()))
                .anyMatch(defined -> defined.equals(expected)
                        || defined.startsWith(expected + ".") || defined.startsWith(expected + "["));
    }
}
