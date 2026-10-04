package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Set;

@Component
public class CheckDdlAutoRule extends AbstractConfigRule {
    private static final String DDL_AUTO = "spring.jpa.hibernate.ddl-auto";

    // Значения, при которых Hibernate сам меняет или пересоздает схему
    private static final Set<String> SCHEMA_CHANGING_VALUES = Set.of("update", "create", "create-drop");

    @Override
    public String code() {
        return RuleCodes.CHECK_DDL_AUTO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует файлы настроек и ищет автоматическое изменение схемы БД (ddl-auto)";
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        // В профилях разработки и тестов пересоздание схемы уместно
        if (configFile.isNonProduction()) {
            return List.of();
        }
        return configFile.find(DDL_AUTO)
                .filter(property -> SCHEMA_CHANGING_VALUES.contains(property.value().toLowerCase()))
                .map(property -> violation(configFile, property.line(),
                        DDL_AUTO + "=" + property.value() + ": Hibernate сам меняет схему при старте - create"
                                + " стирает данные, update не умеет переименовывать и удалять колонки и расходится"
                                + " со схемой молча; задайте validate, а схему ведите миграциями (Flyway, Liquibase)"))
                .stream()
                .toList();
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
