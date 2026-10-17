package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
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
import ru.akvine.zond.rules.files.TextFiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SqlNotNullWithoutDefaultRule extends AbstractContextRule {
    // alter table orders add column status varchar(20) not null
    private static final Pattern ADD_NOT_NULL_COLUMN = Pattern.compile(
            "^alter\\s+table\\s+\\S+\\s+add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?"
                    + "(?!constraint\\b|primary\\b|foreign\\b|unique\\b|check\\b|index\\b)(\\S+)\\s+.*\\bnot\\s+null\\b.*",
            Pattern.CASE_INSENSITIVE);

    // Значение для уже существующих строк база возьмет отсюда
    private static final Pattern HAS_VALUE =
            Pattern.compile(".*\\b(default|generated|identity|serial|bigserial|auto_increment)\\b.*", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.SQL_NOT_NULL_WITHOUT_DEFAULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует миграции (SQL и журналы Liquibase) и ищет добавление колонки NOT NULL без значения по умолчанию";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isMigration(file)) {
                continue;
            }
            List<Liquibase.ChangeSet> changeSets = Liquibase.changeSets(file);
            Set<String> createdHere = new HashSet<>();
            for (SqlStatements.Statement statement : SqlStatements.of(file)) {
                List<DbSchema.Change> changes = DbSchema.changes(statement.text());
                changes.stream()
                        .filter(change -> change.kind() == DbSchema.Kind.CREATE_TABLE)
                        .forEach(change -> createdHere.add(change.table()));
                Matcher matcher = ADD_NOT_NULL_COLUMN.matcher(statement.text());
                if (matcher.matches() && !HAS_VALUE.matcher(statement.text()).matches()
                        && !isNewTableForDatabase(statement, changes, createdHere, changeSets)) {
                    violations.add(violation(file.path(), statement.line(),
                            "Колонка '" + matcher.group(1) + "' добавляется как NOT NULL без DEFAULT: если в таблице"
                                    + " уже есть строки, миграция упадет; задайте DEFAULT либо добавьте колонку"
                                    + " без ограничения, заполните ее и только потом сделайте NOT NULL"));
                }
            }
        }
        return violations;
    }

    // createTable без колонок аудита, а следом наборы context="mysql" и context="postgresql" добавляют их со своим
    // типом для каждой базы: таблицу создает этот же файл, это одна доработка, и строк в таблице еще нет
    private boolean isNewTableForDatabase(SqlStatements.Statement statement, List<DbSchema.Change> changes,
                                          Set<String> createdHere, List<Liquibase.ChangeSet> changeSets) {
        Optional<Liquibase.ChangeSet> changeSet = changeSets.stream()
                .filter(candidate -> candidate.line() <= statement.line())
                .reduce((first, second) -> second);
        return changeSet.filter(found -> !found.condition().isEmpty()).isPresent()
                && changes.stream().anyMatch(change -> change.kind() == DbSchema.Kind.ADD_COLUMN
                && createdHere.contains(change.table()));
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
