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
import ru.akvine.zond.rules.files.SqlStatements;
import ru.akvine.zond.rules.files.TextFiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SqlChangeWithoutWhereRule extends AbstractContextRule {
    private static final Pattern CHANGE = Pattern.compile("^(update|delete\\s+from)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE = Pattern.compile("^update\\s+(\\S+)\\s+set\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    // Имя колонки слева от знака равенства: в начале списка либо после запятой
    private static final Pattern ASSIGNMENT = Pattern.compile("(?:^|,)\\s*([\\w\"`.]+)\\s*=");
    private static final Pattern WHERE = Pattern.compile(".*\\bwhere\\b.*", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.SQL_CHANGE_WITHOUT_WHERE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует миграции (SQL и журналы Liquibase) и ищет UPDATE и DELETE без условия WHERE";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isMigration(file)) {
                continue;
            }
            // Колонки, которые добавлены раньше в этом же файле: их заполняют сразу во всех строках
            Set<String> addedHere = new HashSet<>();
            for (SqlStatements.Statement statement : SqlStatements.of(file)) {
                String text = statement.text();
                for (DbSchema.Change change : DbSchema.changes(text)) {
                    change.columns().forEach(column -> addedHere.add(change.table() + "." + column.name()));
                }
                if (CHANGE.matcher(text).matches() && !WHERE.matcher(text).matches() && !isBackfill(text, addedHere)) {
                    violations.add(violation(file.path(), statement.line(),
                            "'" + text.split("\\s+set\\s+|\\s*$")[0] + "' без WHERE затрагивает все строки таблицы:"
                                    + " на большой таблице это долгая блокировка и огромная транзакция; если так"
                                    + " и задумано, обрабатывайте строки пачками"));
                }
            }
        }
        return violations;
    }

    // update orders set region = 'RU' сразу после alter table orders add region: новой колонке задают
    // значение во всех строках - для того UPDATE без WHERE и написан
    private boolean isBackfill(String statement, Set<String> addedHere) {
        Matcher update = UPDATE.matcher(statement);
        if (!update.matches()) {
            return false;
        }
        String table = DbSchema.name(update.group(1));
        List<String> columns = new ArrayList<>();
        Matcher assignment = ASSIGNMENT.matcher(update.group(2));
        while (assignment.find()) {
            columns.add(table + "." + DbSchema.name(assignment.group(1)));
        }
        return !columns.isEmpty() && addedHere.containsAll(columns);
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
