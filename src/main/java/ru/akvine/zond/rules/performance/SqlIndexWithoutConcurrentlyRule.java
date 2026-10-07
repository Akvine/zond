package ru.akvine.zond.rules.performance;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.enums.TextFileType;
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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SqlIndexWithoutConcurrentlyRule extends AbstractContextRule {
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "^create\\s+(?:unique\\s+)?index\\s+(concurrently\\s+)?.*?\\bon\\s+(?:only\\s+)?([^\\s(]+).*",
            Pattern.CASE_INSENSITIVE);
    private static final String JDBC = "jdbc:";
    private static final String POSTGRES_URL = "jdbc:postgresql";
    private static final String POSTGRES_DRIVER = "org.postgresql";

    @Override
    public String code() {
        return RuleCodes.SQL_INDEX_WITHOUT_CONCURRENTLY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует SQL-миграции и ищет создание индекса на существующей таблице без CONCURRENTLY (PostgreSQL)";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        boolean postgres = usesPostgres(context);
        // База известна, и это не PostgreSQL: слова CONCURRENTLY в ней нет
        if (!postgres && usesAnotherDatabase(context)) {
            return violations;
        }
        for (TextFile file : DbSchema.migrations(context)) {
            if (file.type() != TextFileType.SQL) {
                continue;
            }
            // Только что созданная таблица пуста: индекс на ней строится мгновенно. В SQL-файле формата
            // Liquibase миграция - это набор изменений, а не файл целиком
            Set<String> createdHere = new HashSet<>();
            List<Integer> changeSetLines = Liquibase.changeSetLines(file);
            long currentChangeSet = -1;
            for (SqlStatements.Statement statement : SqlStatements.of(file)) {
                long changeSet = changeSetLines.stream().filter(line -> line <= statement.line()).count();
                if (changeSet != currentChangeSet) {
                    createdHere.clear();
                    currentChangeSet = changeSet;
                }
                DbSchema.changes(statement.text()).stream()
                        .filter(change -> change.kind() == DbSchema.Kind.CREATE_TABLE)
                        .forEach(change -> createdHere.add(change.table()));
                Matcher index = CREATE_INDEX.matcher(statement.text());
                if (!index.matches() || index.group(1) != null || createdHere.contains(DbSchema.name(index.group(2)))) {
                    continue;
                }
                violations.add(violation(file.path(), statement.line(),
                        "Индекс на существующей таблице '" + DbSchema.name(index.group(2)) + "' создается без"
                                + " CONCURRENTLY: на время построения PostgreSQL блокирует запись в таблицу, и на"
                                + " большой таблице приложение встанет; создавайте индекс через CREATE INDEX"
                                + " CONCURRENTLY отдельной миграцией вне транзакции")
                        .withConfidence(postgres ? Confidence.PROBABLE : Confidence.SUSPICION));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private boolean usesPostgres(ScanContext context) {
        boolean inConfig = context.configFiles().stream()
                .flatMap(file -> file.properties().stream())
                .anyMatch(property -> property.value().toLowerCase(Locale.ROOT).contains(POSTGRES_URL));
        return inConfig || context.textFiles().stream()
                .filter(file -> file.type() != TextFileType.SQL)
                .flatMap(file -> file.lines().stream())
                .anyMatch(line -> line.contains(POSTGRES_DRIVER));
    }

    private boolean usesAnotherDatabase(ScanContext context) {
        return context.configFiles().stream()
                .flatMap(file -> file.properties().stream())
                .anyMatch(property -> property.value().toLowerCase(Locale.ROOT).contains(JDBC));
    }
}
