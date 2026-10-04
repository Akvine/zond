package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckSqlForeignKeyWithoutIndexRule extends AbstractContextRule {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(\\S+?)\\s*\\((.*)\\).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^alter\\s+table\\s+(?:only\\s+)?(?:if\\s+exists\\s+)?(\\S+)\\s+(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "^create\\s+(?:unique\\s+)?index\\s+.*?\\bon\\s+(?:only\\s+)?(\\S+?)\\s*(?:using\\s+\\w+\\s*)?\\(\\s*([^,)\\s]+).*",
            Pattern.CASE_INSENSITIVE);

    // foreign key (customer_id) references ...
    private static final Pattern FOREIGN_KEY = Pattern.compile("foreign\\s+key\\s*\\(\\s*([^,)\\s]+)", Pattern.CASE_INSENSITIVE);

    // primary key (id, ...), unique (code): по первой колонке такого ограничения база строит индекс сама
    private static final Pattern KEY_CONSTRAINT =
            Pattern.compile("(?:primary\\s+key|unique)\\s*\\(\\s*([^,)\\s]+)", Pattern.CASE_INSENSITIVE);

    // customer_id bigint references customers(id); id bigint primary key
    private static final Pattern COLUMN = Pattern.compile("^([^\\s(]+)\\s+\\S+.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_REFERENCE = Pattern.compile(".*\\breferences\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_KEY = Pattern.compile(".*\\b(primary\\s+key|unique)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT_START =
            Pattern.compile("^(constraint|primary|foreign|unique|check|index|key)\\b.*", Pattern.CASE_INSENSITIVE);

    /**
     * Внешний ключ: таблица, колонка и место, где он объявлен
     */
    private record ForeignKey(String table, String column, Path file, int line) {
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_SQL_FOREIGN_KEY_WITHOUT_INDEX_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует SQL-миграции и ищет внешние ключи, по колонке которых нет индекса";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        // Индекс может быть создан в другой миграции - сначала собираем их по всем файлам
        List<ForeignKey> foreignKeys = new ArrayList<>();
        Set<String> indexed = new HashSet<>();
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isSql(file)) {
                continue;
            }
            for (SqlStatements.Statement statement : SqlStatements.of(file)) {
                collect(statement, file.path(), foreignKeys, indexed);
            }
        }

        return foreignKeys.stream()
                .filter(key -> !indexed.contains(key.table() + "." + key.column()))
                .map(key -> violation(key.file(), key.line(),
                        "Внешний ключ по колонке '" + key.table() + "." + key.column() + "' без индекса:"
                                + " соединения по этой колонке и удаление строк из родительской таблицы будут"
                                + " читать таблицу целиком; создайте индекс"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private void collect(SqlStatements.Statement statement, Path file, List<ForeignKey> foreignKeys, Set<String> indexed) {
        Matcher index = CREATE_INDEX.matcher(statement.text());
        if (index.matches()) {
            indexed.add(name(index.group(1)) + "." + name(index.group(2)));
            return;
        }

        Matcher create = CREATE_TABLE.matcher(statement.text());
        Matcher alter = ALTER_TABLE.matcher(statement.text());
        if (create.matches()) {
            String table = name(create.group(1));
            for (String part : splitTopLevel(create.group(2))) {
                collectDefinition(part.trim(), table, statement, file, foreignKeys, indexed);
            }
        } else if (alter.matches()) {
            collectConstraints(alter.group(2), name(alter.group(1)), statement, file, foreignKeys, indexed);
        }
    }

    // Одно определение из скобок create table: колонка либо ограничение
    private void collectDefinition(
            String definition, String table, SqlStatements.Statement statement, Path file,
            List<ForeignKey> foreignKeys, Set<String> indexed) {
        if (CONSTRAINT_START.matcher(definition).matches()) {
            collectConstraints(definition, table, statement, file, foreignKeys, indexed);
            return;
        }
        Matcher column = COLUMN.matcher(definition);
        if (!column.matches()) {
            return;
        }
        String name = name(column.group(1));
        if (INLINE_KEY.matcher(definition).matches()) {
            indexed.add(table + "." + name);
        }
        if (INLINE_REFERENCE.matcher(definition).matches()) {
            foreignKeys.add(new ForeignKey(table, name, file, statement.line()));
        }
    }

    private void collectConstraints(
            String text, String table, SqlStatements.Statement statement, Path file,
            List<ForeignKey> foreignKeys, Set<String> indexed) {
        Matcher foreignKey = FOREIGN_KEY.matcher(text);
        while (foreignKey.find()) {
            foreignKeys.add(new ForeignKey(table, name(foreignKey.group(1)), file, statement.line()));
        }
        Matcher key = KEY_CONSTRAINT.matcher(text);
        while (key.find()) {
            indexed.add(table + "." + name(key.group(1)));
        }
    }

    // Запятые внутри скобок (numeric(10, 2), primary key (a, b)) определения не разделяют
    private List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < body.length(); index++) {
            char symbol = body.charAt(index);
            if (symbol == '(') {
                depth++;
            } else if (symbol == ')') {
                depth--;
            } else if (symbol == ',' && depth == 0) {
                parts.add(body.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    // "public"."Orders" -> orders
    private String name(String raw) {
        String cleaned = raw.replaceAll("[\"`\\[\\]]", "").toLowerCase(Locale.ROOT);
        return cleaned.substring(cleaned.lastIndexOf('.') + 1);
    }
}
