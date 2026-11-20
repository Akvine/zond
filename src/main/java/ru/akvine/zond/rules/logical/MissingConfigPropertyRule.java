package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.TextFiles;
import ru.akvine.zond.rules.support.ScanRoots;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.ValuePlaceholders;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MissingConfigPropertyRule extends AbstractContextRule {
    private static final Pattern CONDITIONAL = Pattern.compile("Profile|Conditional\\w*");
    // instance-id -> INSTANCE_ID: все, кроме букв и цифр, в имени переменной окружения заменяется подчеркиванием
    private static final Pattern NOT_VARIABLE_SYMBOL = Pattern.compile("[^A-Z0-9]");
    // java.io.tmpdir, user.home, os.name: системные свойства JVM задает сама
    private static final Set<String> SYSTEM_PROPERTY_PREFIXES = Set.of("java.", "user.", "os.", "file.", "sun.");
    private static final Set<String> SYSTEM_PROPERTIES = Set.of("line.separator", "path.separator");

    @Override
    public String code() {
        return RuleCodes.MISSING_CONFIG_PROPERTY_RULE_CODE;
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
        // Бин из пакета, который не сканирует ни одно приложение проекта (клиент для другого продукта),
        // здесь не создается, и его свойства в настройках этого приложения не нужны
        ScanRoots roots = ScanRoots.of(context.sources());
        for (SourceFile sourceFile : context.sources()) {
            for (AnnotationExpr annotation : sourceFile.findAll(AnnotationExpr.class)) {
                if (!ValuePlaceholders.isValue(annotation) || TestClasses.isInside(annotation)
                        || annotation.findAncestor(ClassOrInterfaceDeclaration.class).filter(type -> !roots.isCreated(type)).isPresent()) {
                    continue;
                }
                for (ValuePlaceholders.Placeholder placeholder : ValuePlaceholders.of(annotation)) {
                    String key = placeholder.key();
                    boolean defined = context.configFiles().stream().anyMatch(file -> ValuePlaceholders.isDefinedIn(key, file));
                    // Переменную окружения (DB_HOST) в файлах настроек и не задают
                    if (!placeholder.hasDefault() && !ValuePlaceholders.isEnvironmentVariable(key) && !defined
                            && !isSystemProperty(key) && !isPassedFromOutside(key, context)) {
                        Violation violation = violation(sourceFile, annotation,
                                "Свойства '" + key + "' нет ни в одном файле настроек, а значения по умолчанию"
                                        + " у @Value нет: если его не передают снаружи (переменная окружения,"
                                        + " аргумент запуска), приложение не запустится; добавьте свойство либо"
                                        + " значение по умолчанию - ${" + key + ":...}");
                        // @Profile("stand") @Configuration class LoggingConfig: бин создается только на стенде, а его
                        // настройки лежат там же и в репозиторий не попадают
                        violations.add(isConditional(annotation)
                                ? violation.withConfidence(Confidence.SUSPICION)
                                : violation);
                    }
                }
            }
        }
        return violations;
    }

    // Бин под @Profile либо @ConditionalOn...: нужен не при каждом запуске
    private boolean isConditional(AnnotationExpr value) {
        return value.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> type.getAnnotations().stream()
                        .anyMatch(annotation -> CONDITIONAL.matcher(annotation.getName().getIdentifier()).matches()))
                .isPresent();
    }

    /**
     * Свойство instance-id Spring возьмет и из переменной окружения INSTANCE_ID. Если она или само свойство
     * названы в docker-compose, манифесте Kubernetes, Dockerfile или файле CI, значение приходит оттуда
     */
    private boolean isPassedFromOutside(String key, ScanContext context) {
        String variable = NOT_VARIABLE_SYMBOL.matcher(key.toUpperCase(Locale.ROOT)).replaceAll("_");
        Pattern mention = Pattern.compile(
                "(?<![\\w.-])(" + Pattern.quote(variable) + "|" + Pattern.quote(key) + ")(?![\\w.-])");
        return context.textFiles().stream()
                .filter(file -> !TextFiles.isMigration(file))
                .flatMap(file -> file.lines().stream())
                .anyMatch(line -> mention.matcher(line).find());
    }

    private boolean isSystemProperty(String key) {
        return SYSTEM_PROPERTIES.contains(key) || SYSTEM_PROPERTY_PREFIXES.stream().anyMatch(key::startsWith);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
