package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ConfigKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Component
public class CheckProfilePropertyMissingRule extends AbstractContextRule {
    private static final int SHOWN_KEYS = 5;
    private static final int MIN_PROFILES = 2;

    @Override
    public String code() {
        return RuleCodes.CHECK_PROFILE_PROPERTY_MISSING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует настройки профилей и ищет свои свойства, которые заданы в одном профиле и пропущены в другом";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        // Каталог и имя без профиля -> файлы одного набора настроек: application, application-dev, application-prod
        Map<String, List<ConfigFile>> groups = new LinkedHashMap<>();
        for (ConfigFile file : context.configFiles()) {
            groups.computeIfAbsent(file.path().toAbsolutePath().getParent() + "/" + ConfigKeys.baseName(file.path()),
                    key -> new ArrayList<>()).add(file);
        }

        List<Violation> violations = new ArrayList<>();
        for (List<ConfigFile> group : groups.values()) {
            check(group, violations);
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private void check(List<ConfigFile> group, List<Violation> violations) {
        // Свойство из основного файла действует во всех профилях - о нем беспокоиться не нужно
        Set<String> common = new HashSet<>();
        Map<String, Map<String, String>> byProfile = new LinkedHashMap<>();
        Map<String, ConfigFile> files = new LinkedHashMap<>();
        for (ConfigFile file : group) {
            String profile = ConfigKeys.profile(file.path());
            Map<String, String> keys = profile.isEmpty() ? null : byProfile.computeIfAbsent(profile, key -> new LinkedHashMap<>());
            file.properties().stream()
                    .filter(property -> !ConfigKeys.isFramework(property.key()))
                    .forEach(property -> {
                        if (keys == null) {
                            common.add(ConfigKeys.normalize(property.key()));
                        } else {
                            keys.putIfAbsent(ConfigKeys.normalize(property.key()), property.key());
                        }
                    });
            if (!profile.isEmpty()) {
                files.putIfAbsent(profile, file);
            }
        }
        if (byProfile.size() < MIN_PROFILES) {
            return;
        }

        byProfile.forEach((profile, keys) -> {
            Set<String> missing = new TreeSet<>();
            byProfile.forEach((other, otherKeys) -> otherKeys.forEach((normalized, key) -> {
                if (!other.equals(profile) && !keys.containsKey(normalized) && !common.contains(normalized)) {
                    missing.add(key);
                }
            }));
            if (!missing.isEmpty()) {
                List<String> shown = new ArrayList<>(missing).subList(0, Math.min(SHOWN_KEYS, missing.size()));
                violations.add(violation(files.get(profile).path(),
                        "В профиле '" + profile + "' нет свойств, которые заданы в других профилях"
                                + " и отсутствуют в основном файле (" + missing.size() + "): " + String.join(", ", shown)
                                + (missing.size() > SHOWN_KEYS ? " и другие" : "")
                                + "; с этим профилем значение взять будет неоткуда"));
            }
        });
    }
}
