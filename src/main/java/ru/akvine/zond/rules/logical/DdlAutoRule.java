package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DdlAutoRule extends AbstractConfigRule {
    private static final String DDL_AUTO = "spring.jpa.hibernate.ddl-auto";

    // Значения, при которых Hibernate сам меняет или пересоздает схему
    private static final List<String> DATASOURCE_URLS = List.of("spring.datasource.url", "spring.datasource.jdbc-url");
    // jdbc:h2:mem:shop, jdbc:hsqldb:mem:shop, jdbc:derby:memory:shop, jdbc:sqlite::memory:
    private static final Pattern IN_MEMORY_URL =
            Pattern.compile("(?i)^jdbc:(h2:mem:|hsqldb:mem:|derby:memory:|sqlite::memory:)");
    private static final Set<String> SCHEMA_CHANGING_VALUES = Set.of("update", "create", "create-drop");

    @Override
    public String code() {
        return RuleCodes.DDL_AUTO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет автоматическое изменение схемы БД (ddl-auto)";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        // В профилях разработки и тестов пересоздание схемы уместно; база в памяти (H2, HSQLDB, Derby) живет
        // только пока работает приложение, и создавать ее схему при старте - единственный способ ее получить
        if (isInMemoryDatabase(configFile)) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        for (ConfigProperty property : configFile.productionProperties()) {
            if (isDdlAuto(property) && SCHEMA_CHANGING_VALUES.contains(property.value().toLowerCase(Locale.ROOT))) {
                violations.add(violation(configFile, property.line(),
                        DDL_AUTO + "=" + property.value() + ": Hibernate сам меняет схему при старте - create"
                                + " стирает данные, update не умеет переименовывать и удалять колонки и расходится"
                                + " со схемой молча; задайте validate, а схему ведите миграциями (Flyway, Liquibase)"));
            }
        }
        return violations;
    }

    // Spring не различает ddl-auto, ddl_auto и ddlAuto
    private boolean isDdlAuto(ConfigProperty property) {
        return normalize(property.key()).equals(normalize(DDL_AUTO));
    }

    private String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private boolean isInMemoryDatabase(ConfigFile configFile) {
        return DATASOURCE_URLS.stream()
                .map(configFile::find)
                .flatMap(Optional::stream)
                .anyMatch(url -> IN_MEMORY_URL.matcher(url.value()).find());
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
