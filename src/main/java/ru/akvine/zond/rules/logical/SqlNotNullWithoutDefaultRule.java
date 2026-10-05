package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.SqlStatements;
import ru.akvine.zond.rules.files.TextFiles;

import java.util.ArrayList;
import java.util.List;
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
            for (SqlStatements.Statement statement : SqlStatements.of(file)) {
                Matcher matcher = ADD_NOT_NULL_COLUMN.matcher(statement.text());
                if (matcher.matches() && !HAS_VALUE.matcher(statement.text()).matches()) {
                    violations.add(violation(file.path(), statement.line(),
                            "Колонка '" + matcher.group(1) + "' добавляется как NOT NULL без DEFAULT: если в таблице"
                                    + " уже есть строки, миграция упадет; задайте DEFAULT либо добавьте колонку"
                                    + " без ограничения, заполните ее и только потом сделайте NOT NULL"));
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
