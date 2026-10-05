package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ConfigKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ConflictingConfigValuesRule extends AbstractContextRule {
    private static final String PROPERTIES_EXTENSION = ".properties";

    @Override
    public String code() {
        return RuleCodes.CONFLICTING_CONFIG_VALUES_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует настройки и ищет свойства, заданные по-разному в .properties и .yml одного профиля";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        // Каталог, имя и профиль -> файл .properties: при совпадении ключей Spring Boot берет значение из него
        Map<String, ConfigFile> winners = new LinkedHashMap<>();
        for (ConfigFile file : context.configFiles()) {
            if (isProperties(file)) {
                winners.putIfAbsent(groupOf(file), file);
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (ConfigFile file : context.configFiles()) {
            ConfigFile winner = winners.get(groupOf(file));
            if (isProperties(file) || winner == null) {
                continue;
            }
            for (ConfigProperty property : file.properties()) {
                Optional<ConfigProperty> other = winner.find(property.key());
                if (other.isPresent() && !other.get().value().equals(property.value())) {
                    violations.add(violation(file.path(), property.line(),
                            "Свойство '" + property.key() + "' задано и здесь, и в '" + winner.path().getFileName()
                                    + "' с другим значением: действует значение из .properties, а это молча"
                                    + " игнорируется; оставьте свойство в одном файле"));
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

    private boolean isProperties(ConfigFile file) {
        return file.path().getFileName().toString().endsWith(PROPERTIES_EXTENSION);
    }

    private String groupOf(ConfigFile file) {
        return file.path().toAbsolutePath().getParent() + "/" + ConfigKeys.baseName(file.path())
                + "-" + ConfigKeys.profile(file.path());
    }
}
