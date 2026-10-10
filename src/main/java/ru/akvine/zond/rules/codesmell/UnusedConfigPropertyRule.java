package ru.akvine.zond.rules.codesmell;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ConfigKeys;
import ru.akvine.zond.rules.support.StringLiterals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class UnusedConfigPropertyRule extends AbstractContextRule {
    private static final String TEST_DIRECTORY = "test";

    /**
     * Место, где свойство задано
     */
    private record Place(ConfigFile file, ConfigProperty property) {
    }

    @Override
    public String code() {
        return RuleCodes.UNUSED_CONFIG_PROPERTY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и настройки и ищет свои свойства, которые в коде нигде не читаются";
    }

    // Свойство может читать библиотека или код, который собирает ключ из частей: этого по проекту не видно
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        // Кода в проверке нет - сказать, что свойство никто не читает, нельзя
        if (context.sources().isEmpty()) {
            return violations;
        }

        // Все строки из кода и все значения настроек: свойство читают через @Value("${...}"),
        // @ConfigurationProperties(prefix), environment.getProperty(...) либо подставляют в другое свойство
        Set<String> literals = new HashSet<>();
        StringBuilder text = new StringBuilder();
        for (SourceFile sourceFile : context.sources()) {
            for (StringLiterals.LiteralText literal : StringLiterals.findComplete(sourceFile.unit())) {
                String normalized = ConfigKeys.normalize(literal.text());
                literals.add(normalized);
                text.append(normalized).append('\n');
            }
        }
        for (ConfigFile configFile : context.configFiles()) {
            configFile.properties().forEach(property -> text.append(ConfigKeys.normalize(property.value())).append('\n'));
        }
        // Свойство читают и не из Java: <springProperty source="..."> в logback, ${...} в XML и манифестах
        for (TextFile textFile : context.textFiles()) {
            textFile.lines().forEach(line -> text.append(ConfigKeys.normalize(line)).append('\n'));
        }

        // Одно и то же свойство повторяется в файлах всех профилей: это одна находка, а не по одной на файл
        String haystack = text.toString();
        Map<String, List<Place>> unused = new LinkedHashMap<>();
        // Разделы настроек (первое слово ключа), из которых код читает хотя бы одно свойство
        Set<String> ownSections = new HashSet<>();
        for (ConfigFile configFile : context.configFiles()) {
            for (ConfigProperty property : configFile.properties()) {
                String key = ConfigKeys.normalize(property.key());
                if (ConfigKeys.isFramework(property.key())) {
                    continue;
                }
                if (haystack.contains(key) || hasKnownPrefix(key, literals)) {
                    ownSections.add(sectionOf(key));
                } else {
                    unused.computeIfAbsent(key, name -> new ArrayList<>()).add(new Place(configFile, property));
                }
            }
        }
        for (List<Place> places : unused.values()) {
            // Показываем в основном файле приложения, если свойство там есть: настройки тестов - в последнюю очередь
            Place shown = places.stream()
                    .min(Comparator.comparing((Place place) -> isTestFile(place.file().path()))
                            .thenComparing(place -> !ConfigKeys.profile(place.file().path()).isEmpty()))
                    .orElseThrow();
            long others = places.stream().map(place -> place.file().path()).distinct().count() - 1;
            // Из этого раздела код не читает ничего: скорее всего, весь раздел принадлежит библиотеке
            boolean foreignSection = !ownSections.contains(sectionOf(ConfigKeys.normalize(shown.property().key())));
            Violation violation = violation(shown.file().path(), shown.property().line(),
                    "Свойство '" + shown.property().key() + "' в коде нигде не читается"
                            + (others > 0 ? " (задано еще в " + others + " файл(ах) настроек)" : "")
                            + ": похоже, оно осталось от удаленного кода либо в имени опечатка; удалите его или"
                            + " исправьте имя. Если его читает библиотека, скройте находку комментарием zond:ignore");
            violations.add(foreignSection ? violation.withConfidence(Confidence.SUSPICION) : violation);
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

    // app.mail.host -> app
    private String sectionOf(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(0, dot);
    }

    private boolean isTestFile(Path path) {
        for (Path part : path) {
            if (TEST_DIRECTORY.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    // В коде записана только часть ключа: @ConfigurationProperties("app.mail") читает все свойства app.mail.*,
    // @ConditionalOnProperty(prefix = "app.feature", name = "enabled") - свойство app.feature.enabled
    private boolean hasKnownPrefix(String key, Set<String> literals) {
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1)) {
            if (literals.contains(key.substring(0, dot))) {
                return true;
            }
        }
        return false;
    }
}
