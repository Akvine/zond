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
public class CheckSqlDestructiveStatementRule extends AbstractContextRule {
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "^(drop\\s+table|truncate)\\b.*|^alter\\s+table\\s+.*\\bdrop\\s+(column\\s+)?(?!constraint\\b|index\\b)\\w+.*",
            Pattern.CASE_INSENSITIVE);
    private static final int SNIPPET_LENGTH = 60;

    @Override
    public String code() {
        return RuleCodes.CHECK_SQL_DESTRUCTIVE_STATEMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует миграции (SQL и журналы Liquibase) и ищет команды, которые безвозвратно удаляют данные: DROP TABLE, DROP COLUMN, TRUNCATE";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isMigration(file)) {
                continue;
            }
            for (SqlStatements.Statement statement : SqlStatements.of(file)) {
                if (DESTRUCTIVE.matcher(statement.text()).matches()) {
                    violations.add(violation(file.path(), statement.line(),
                            "'" + snippet(statement.text()) + "' безвозвратно удаляет данные, а откатить миграцию"
                                    + " после этого нечем; убедитесь, что прежняя версия приложения к ним уже"
                                    + " не обращается, и сохраните копию данных"));
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

    private String snippet(String text) {
        return text.length() <= SNIPPET_LENGTH ? text : text.substring(0, SNIPPET_LENGTH) + "...";
    }
}
