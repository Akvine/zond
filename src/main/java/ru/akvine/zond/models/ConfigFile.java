package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Разобранный файл настроек: application.properties / application.yml
 *
 * @param profileLines строки свойств из документов файла, которые действуют только в профилях разработки
 *                     и тестов: {@code spring.config.activate.on-profile: dev} после разделителя {@code ---}
 */
public record ConfigFile(Path path, List<ConfigProperty> properties, Set<Integer> profileLines) {
    // Профили, которые в рабочую среду не попадают: разработка, тесты, локальный запуск, примеры. Профиля docker
    // здесь нет намеренно: так называют и локальный запуск в контейнерах, и рабочее развертывание
    private static final Set<String> NON_PRODUCTION_PROFILES = Set.of(
            "dev", "develop", "development", "local", "localhost", "test", "tests", "testing", "it", "itest",
            "integrationtest", "e2e", "ci", "h2", "hsql", "hsqldb", "embedded", "inmemory", "mock", "mocks", "stub",
            "sample", "samples", "example", "examples", "demo", "sandbox", "debug");
    // application-prod-debug.yml: рабочая среда названа прямо, и остальные слова в имени ее не отменяют
    private static final Set<String> PRODUCTION_PROFILES = Set.of(
            "prod", "production", "live", "release", "stage", "staging", "preprod", "uat");
    // Каталог с настройками одной среды: config/dev/application.yml, profiles/local/core.properties
    private static final Set<String> NON_PRODUCTION_DIRECTORIES = Set.of(
            "dev", "develop", "development", "local", "localhost", "sandbox", "demo");
    private static final Pattern NAME_SEPARATOR = Pattern.compile("[-_.]");
    private static final Pattern PROFILE_SEPARATOR = Pattern.compile("[,|&()\\s]+");
    private static final String NEGATION = "!";

    public ConfigFile(Path path, List<ConfigProperty> properties) {
        this(path, properties, Set.of());
    }

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
     * (application-dev.yml, application-local-h2.properties, config/dev/application.yml), образцы
     * (application-sample.yml) и все, что лежит в src/test, ci/tests и подобных каталогах
     */
    public boolean isNonProduction() {
        String[] words = NAME_SEPARATOR.split(path.getFileName().toString().toLowerCase(Locale.ROOT));
        boolean named = false;
        // Первое слово - само имя файла (application, bootstrap), последнее - расширение
        for (int index = 1; index < words.length - 1; index++) {
            if (PRODUCTION_PROFILES.contains(words[index])) {
                return TestPaths.isTest(path);
            }
            named |= NON_PRODUCTION_PROFILES.contains(words[index]);
        }
        Path directory = path.getParent() == null ? null : path.getParent().getFileName();
        boolean inProfileDirectory = directory != null
                && NON_PRODUCTION_DIRECTORIES.contains(directory.toString().toLowerCase(Locale.ROOT));
        return named || inProfileDirectory || TestPaths.isTest(path);
    }

    /**
     * @return true, если свойство в рабочую среду не попадает: из-за файла либо из-за документа внутри файла,
     * который включается только в профиле разработки или тестов
     */
    public boolean isNonProduction(ConfigProperty property) {
        return profileLines.contains(property.line()) || isNonProduction();
    }

    /**
     * @return свойства, которые действуют в рабочей среде
     */
    public List<ConfigProperty> productionProperties() {
        if (isNonProduction()) {
            return List.of();
        }
        return profileLines.isEmpty()
                ? properties
                : properties.stream().filter(property -> !profileLines.contains(property.line())).toList();
    }

    /**
     * @param expression значение spring.config.activate.on-profile: {@code dev}, {@code dev | local}, {@code !prod}
     * @return true, если документ с таким условием в рабочей среде не включается
     */
    public static boolean isNonProductionProfile(String expression) {
        boolean any = false;
        for (String profile : PROFILE_SEPARATOR.split(expression.trim().toLowerCase(Locale.ROOT))) {
            if (profile.isEmpty()) {
                continue;
            }
            // !prod - "везде, кроме рабочей среды"; !dev - наоборот, в том числе в рабочей
            boolean negated = profile.startsWith(NEGATION);
            String name = negated ? profile.substring(1) : profile;
            boolean nonProduction = negated
                    ? PRODUCTION_PROFILES.contains(name)
                    : NON_PRODUCTION_PROFILES.contains(name);
            if (!nonProduction) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private String normalize(String key) {
        return key.toLowerCase().replace("-", "").replace("_", "");
    }
}
