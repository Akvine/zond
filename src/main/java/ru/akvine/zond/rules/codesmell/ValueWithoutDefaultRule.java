package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.expr.AnnotationExpr;
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
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.ValuePlaceholders;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ValueWithoutDefaultRule extends AbstractContextRule {
    private static final String FROM_TESTS = " из тестов";

    @Override
    public String code() {
        return RuleCodes.VALUE_WITHOUT_DEFAULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет код с настройками и ищет @Value без значения по умолчанию, для которого свойство"
                + " не задано в основном файле настроек";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
                if (!ValuePlaceholders.isValue(annotation)) {
                    continue;
                }
                boolean inTest = TestClasses.isInside(annotation);
                ValuePlaceholders.of(annotation).stream()
                        .filter(placeholder -> !placeholder.hasDefault())
                        .map(placeholder -> describe(placeholder.key(), context.configFiles(), inTest))
                        .flatMap(Optional::stream)
                        .findFirst()
                        .ifPresent(problem -> violations.add(violation(sourceFile, annotation,
                                "@Value(\"" + ValuePlaceholders.textOf(annotation) + "\") без значения по умолчанию"
                                        + problem)));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    /**
     * @return чем грозит отсутствие значения по умолчанию; пусто, если свойство задано в основном файле настроек
     * и потому есть в любом окружении
     */
    private Optional<String> describe(String key, List<ConfigFile> configFiles, boolean inTest) {
        // Настройки в проверку не попали: задано свойство или нет, неизвестно
        if (configFiles.isEmpty()) {
            return Optional.of(": если свойства нет в окружении, приложение не стартует; если свойство"
                    + " необязательное, задайте значение по умолчанию: ${ключ:значение}");
        }
        List<ConfigFile> defining = configFiles.stream()
                .filter(file -> ValuePlaceholders.isDefinedIn(key, file))
                .toList();
        // Основной файл читается при любом профиле; настройки тестов коду приложения не достаются
        boolean inMainFile = defining.stream()
                .anyMatch(file -> ConfigKeys.profile(file.path()).isEmpty() && (inTest || !file.isNonProduction()));
        if (inMainFile) {
            return Optional.empty();
        }
        if (defining.isEmpty()) {
            // О свойстве, которого нет ни в одном файле, сообщает MissingConfigPropertyRule. Переменную окружения
            // в файлах и не задают - о ней говорим здесь
            return ValuePlaceholders.isEnvironmentVariable(key)
                    ? Optional.of(", а '" + key + "' - переменная окружения: там, где ее не задали, приложение"
                    + " не запустится; если она необязательна, задайте значение по умолчанию: ${" + key + ":...}")
                    : Optional.empty();
        }
        String places = defining.stream()
                .map(file -> file.path().getFileName() + (ConfigKeys.profile(file.path()).isEmpty() ? FROM_TESTS : ""))
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
        return Optional.of(", а свойство '" + key + "' задано только в " + places + ": в окружении с другим"
                + " профилем приложение не запустится; задайте свойство в основном файле настроек либо значение"
                + " по умолчанию: ${" + key + ":...}");
    }
}
