package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.ScanRoots;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.ValuePlaceholders;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class MissingConfigPropertyRule extends AbstractContextRule {
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
            for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
                if (!ValuePlaceholders.isValue(annotation) || TestClasses.isInside(annotation)
                        || annotation.findAncestor(ClassOrInterfaceDeclaration.class).filter(type -> !roots.isCreated(type)).isPresent()) {
                    continue;
                }
                for (ValuePlaceholders.Placeholder placeholder : ValuePlaceholders.of(annotation)) {
                    String key = placeholder.key();
                    boolean defined = context.configFiles().stream().anyMatch(file -> ValuePlaceholders.isDefinedIn(key, file));
                    // Переменную окружения (DB_HOST) в файлах настроек и не задают
                    if (!placeholder.hasDefault() && !ValuePlaceholders.isEnvironmentVariable(key) && !defined
                            && !isSystemProperty(key)) {
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
