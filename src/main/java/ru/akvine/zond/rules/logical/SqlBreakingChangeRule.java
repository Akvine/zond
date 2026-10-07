package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.DbSchema;
import ru.akvine.zond.rules.files.Liquibase;
import ru.akvine.zond.rules.files.SqlStatements;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class SqlBreakingChangeRule extends AbstractContextRule {
    private static final String ADVICE = " версия приложения, которая работает во время выкладки, обращается к старому"
            + " имени и начнет падать; переименовывайте в несколько шагов: добавить новое, писать в оба,"
            + " перевести чтение, удалить старое";

    @Override
    public String code() {
        return RuleCodes.SQL_BREAKING_CHANGE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует миграции (SQL и журналы Liquibase) и ищет переименование таблиц и колонок и смену типа колонки, которые ломают работающую версию приложения";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : DbSchema.migrations(context)) {
            // Таблицу и колонку, созданные в этой же миграции, прежняя версия приложения еще не читает.
            // В журнале Liquibase миграция - это набор изменений: один файл копит их за все время
            Set<String> createdHere = new HashSet<>();
            List<Integer> changeSetLines = Liquibase.changeSetLines(file);
            long currentChangeSet = -1;
            for (SqlStatements.Statement statement : SqlStatements.of(file)) {
                long changeSet = changeSetLines.stream().filter(line -> line <= statement.line()).count();
                if (changeSet != currentChangeSet) {
                    createdHere.clear();
                    currentChangeSet = changeSet;
                }
                for (DbSchema.Change change : DbSchema.changes(statement.text())) {
                    String column = change.table() + "." + change.column();
                    switch (change.kind()) {
                        case CREATE_TABLE -> createdHere.add(change.table());
                        case ADD_COLUMN -> createdHere.add(column);
                        case RENAME_TABLE -> {
                            if (!createdHere.contains(change.table())) {
                                violations.add(violation(file.path(), statement.line(),
                                        "Таблица '" + change.table() + "' переименована в '" + change.value() + "':"
                                                + ADVICE));
                            }
                        }
                        case RENAME_COLUMN -> {
                            if (!createdHere.contains(change.table()) && !createdHere.contains(column)) {
                                violations.add(violation(file.path(), statement.line(),
                                        "Колонка '" + column + "' переименована в '" + change.value() + "':" + ADVICE));
                            }
                        }
                        case CHANGE_TYPE -> {
                            if (!createdHere.contains(change.table()) && !createdHere.contains(column)) {
                                // Расширение типа безопасно, сужение - нет; по одной команде их не различить
                                violations.add(violation(file.path(), statement.line(),
                                        "У колонки '" + column + "' меняется тип на '" + change.value() + "': на большой"
                                                + " таблице это долгая блокировка, а несовместимый тип сломает версию"
                                                + " приложения, которая работает во время выкладки; меняйте тип через"
                                                + " новую колонку").withConfidence(Confidence.SUSPICION));
                            }
                        }
                        default -> {
                            // остальные изменения работающей версии не мешают либо проверяются другими правилами
                        }
                    }
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
}
