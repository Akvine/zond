package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CheckSqlChangeWithoutWhereRule extends AbstractContextRule {
    private static final Pattern CHANGE = Pattern.compile("^(update|delete\\s+from)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE = Pattern.compile(".*\\bwhere\\b.*", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_SQL_CHANGE_WITHOUT_WHERE_RULE_CODE;
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
            for (SqlStatements.Statement statement : SqlStatements.of(file)) {
                String text = statement.text();
                if (CHANGE.matcher(text).matches() && !WHERE.matcher(text).matches()) {
                    violations.add(violation(file.path(), statement.line(),
                            "'" + text.split("\\s+set\\s+|\\s*$")[0] + "' без WHERE затрагивает все строки таблицы:"
                                    + " на большой таблице это долгая блокировка и огромная транзакция; если так"
                                    + " и задумано, обрабатывайте строки пачками"));
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
