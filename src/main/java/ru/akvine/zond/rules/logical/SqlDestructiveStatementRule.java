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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SqlDestructiveStatementRule extends AbstractContextRule {
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "^(drop\\s+table|truncate)\\b.*|^alter\\s+table\\s+.*\\bdrop\\s+(column\\s+)?(?!constraint\\b|index\\b|not\\s+null\\b|default\\b|identity\\b|primary\\b|foreign\\b)\\w+.*",
            Pattern.CASE_INSENSITIVE);
    private static final int SNIPPET_LENGTH = 60;
    // DROP TABLE IF EXISTS cards; CREATE TABLE cards (...): скрипт пересоздает схему (schema.sql), а не мигрирует данные
    private static final Pattern DROP_IF_EXISTS =
            Pattern.compile("^drop\\s+table\\s+if\\s+exists\\s+([\\w.\"`]+).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE =
            Pattern.compile("^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\"`]+).*", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.SQL_DESTRUCTIVE_STATEMENT_RULE_CODE;
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
            List<SqlStatements.Statement> statements = SqlStatements.of(file);
            Set<String> created = createdTables(statements);
            for (SqlStatements.Statement statement : statements) {
                if (DESTRUCTIVE.matcher(statement.text()).matches() && !isRecreated(statement.text(), created)) {
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

    private Set<String> createdTables(List<SqlStatements.Statement> statements) {
        Set<String> tables = new HashSet<>();
        for (SqlStatements.Statement statement : statements) {
            Matcher create = CREATE_TABLE.matcher(statement.text());
            if (create.matches()) {
                tables.add(normalize(create.group(1)));
            }
        }
        return tables;
    }

    private boolean isRecreated(String statement, Set<String> created) {
        Matcher drop = DROP_IF_EXISTS.matcher(statement);
        return drop.matches() && created.contains(normalize(drop.group(1)));
    }

    private String normalize(String table) {
        return table.replaceAll("[\"`]", "").toLowerCase(Locale.ROOT);
    }
}
