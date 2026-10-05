package ru.akvine.zond.rules.codesmell;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ConfigKeys;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class UnusedConfigPropertyRule extends AbstractContextRule {
    @Override
    public String code() {
        return RuleCodes.UNUSED_CONFIG_PROPERTY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и настройки и ищет свои свойства, которые в коде нигде не читаются";
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

        String haystack = text.toString();
        for (ConfigFile configFile : context.configFiles()) {
            for (ConfigProperty property : configFile.properties()) {
                String key = ConfigKeys.normalize(property.key());
                if (!ConfigKeys.isFramework(property.key()) && !haystack.contains(key) && !hasKnownPrefix(key, literals)) {
                    violations.add(violation(configFile.path(), property.line(),
                            "Свойство '" + property.key() + "' в коде нигде не читается: похоже, оно осталось"
                                    + " от удаленного кода либо в имени опечатка; удалите его или исправьте имя"));
                }
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
