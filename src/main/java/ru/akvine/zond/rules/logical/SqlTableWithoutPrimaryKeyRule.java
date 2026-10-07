package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.DbSchema;

import java.util.ArrayList;
import java.util.List;

@Component
public class SqlTableWithoutPrimaryKeyRule extends AbstractContextRule {
    @Override
    public String code() {
        return RuleCodes.SQL_TABLE_WITHOUT_PRIMARY_KEY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует миграции (SQL и журналы Liquibase) и ищет таблицы, у которых нет первичного ключа";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        // Смотрим на итог всех миграций: ключ могли добавить отдельной командой позже
        for (DbSchema.Table table : DbSchema.of(context).tables()) {
            // У таблицы-связки многие-ко-многим строку определяет пара внешних ключей: отдельный ключ ей не обязателен
            if (table.hasPrimaryKey() || table.isOpaque() || table.isLinkTable() || table.columns().isEmpty()) {
                continue;
            }
            violations.add(violation(table.file(), table.line(),
                    "У таблицы '" + table.name() + "' нет первичного ключа: строку нельзя однозначно найти,"
                            + " изменить или удалить, возможны полные дубликаты, а логическая репликация таких"
                            + " таблиц не работает; добавьте первичный ключ"));
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
}
