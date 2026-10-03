package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Разобранный файл настроек: application.properties / application.yml
 */
public record ConfigFile(Path path, List<ConfigProperty> properties) {
    // application-dev.yml, application-test.properties и все, что лежит в src/test
    private static final Pattern NON_PRODUCTION_NAME = Pattern.compile(".*-(dev|local|test|it)\\.[a-z]+$");
    private static final String TEST_DIRECTORY = "test";

    /**
     * Spring не различает ddl-auto, ddl_auto и ddlAuto - сравниваем ключи так же
     */
    public Optional<ConfigProperty> find(String key) {
        String expected = normalize(key);
        return properties.stream()
                .filter(property -> normalize(property.key()).equals(expected))
                .findFirst();
    }

    /**
     * @return true для настроек, которые в рабочую среду не попадают: профили разработки и тестов
     */
    public boolean isNonProduction() {
        if (NON_PRODUCTION_NAME.matcher(path.getFileName().toString()).matches()) {
            return true;
        }
        for (Path part : path) {
            if (TEST_DIRECTORY.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String key) {
        return key.toLowerCase().replace("-", "").replace("_", "");
    }
}
