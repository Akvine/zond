package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.parsers.XmlElement;
import ru.akvine.zond.parsers.XmlParser;
import ru.akvine.zond.parsers.YamlNode;
import ru.akvine.zond.parsers.YamlParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Журнал изменений Liquibase в XML или YAML. Каждое изменение переводится в равнозначную команду SQL,
 * поэтому правила для миграций проверяют журнал так же, как обычный SQL-файл.
 */
@UtilityClass
class Liquibase {
    private static final String ROOT = "databaseChangeLog";
    private static final String CHANGE_SET = "changeSet";
    private static final String CHANGES = "changes";
    private static final String COLUMN = "column";
    private static final String COLUMNS = "columns";
    private static final String CONSTRAINTS = "constraints";
    private static final String WHERE = "where";
    private static final String SQL = "sql";

    // Части набора изменений, которые сами изменениями не являются. Откат (rollback) выполняется,
    // только когда миграцию отменяют, - проверять его как миграцию нельзя
    private static final Set<String> NOT_CHANGES =
            Set.of("rollback", "preConditions", "comment", "validCheckSum", "modifySql", "property");

    private static final String TABLE_NAME = "tableName";
    private static final String COLUMN_NAME = "columnName";
    private static final String COLUMN_NAMES = "columnNames";
    private static final String NAME = "name";
    private static final String TYPE = "type";
    private static final String DEFAULT_VALUE_PREFIX = "defaultValue";
    private static final String VALUE_PREFIX = "value";
    private static final String AUTO_INCREMENT = "autoIncrement";
    private static final String NULLABLE = "nullable";
    private static final String PRIMARY_KEY = "primaryKey";
    private static final String UNIQUE = "unique";
    private static final String REFERENCES = "references";
    private static final String REFERENCED_TABLE = "referencedTableName";
    private static final String TRUE = "true";
    private static final String FALSE = "false";
    private static final String UNKNOWN_TYPE = "unknown";
    private static final String UNNAMED = "unnamed";

    /**
     * Колонка изменения: ее атрибуты вместе с атрибутами вложенного constraints
     */
    private record Column(Map<String, String> attributes, int line) {

        String get(String key) {
            return attributes.getOrDefault(key, "");
        }

        // defaultValue, defaultValueNumeric, defaultValueComputed и прочие; value* заполняет уже существующие строки
        boolean hasValue() {
            return TRUE.equalsIgnoreCase(get(AUTO_INCREMENT)) || attributes.keySet().stream()
                    .anyMatch(key -> key.startsWith(DEFAULT_VALUE_PREFIX) || key.startsWith(VALUE_PREFIX));
        }

        boolean references() {
            return !get(REFERENCES).isEmpty() || !get(REFERENCED_TABLE).isEmpty();
        }

        // references="customers(id)" либо referencedTableName="customers"
        String referenced() {
            return get(REFERENCES).isEmpty() ? get(REFERENCED_TABLE) : get(REFERENCES);
        }
    }

    /**
     * Одно изменение набора: createTable, addColumn, dropTable и так далее
     *
     * @param multiline текст SQL занимает несколько строк и начинается со строки после line
     */
    private record Change(
            String name, Map<String, String> attributes, List<Column> columns, String where, String sql,
            int line, boolean multiline) {

        String get(String key) {
            return attributes.getOrDefault(key, "");
        }
    }

    List<SqlStatements.Statement> statements(TextFile file) {
        List<Change> changes = file.type() == TextFileType.LIQUIBASE_XML ? xmlChanges(file) : yamlChanges(file);
        List<SqlStatements.Statement> statements = new ArrayList<>();
        for (Change change : changes) {
            translate(change, statements);
        }
        return statements;
    }

    private List<Change> xmlChanges(TextFile file) {
        List<Change> changes = new ArrayList<>();
        XmlParser.parse(file.lines()).ifPresent(root -> {
            for (XmlElement changeSet : root.descendants(CHANGE_SET)) {
                for (XmlElement element : changeSet.children()) {
                    if (!NOT_CHANGES.contains(element.name())) {
                        changes.add(xmlChange(element));
                    }
                }
            }
        });
        return changes;
    }

    private Change xmlChange(XmlElement element) {
        List<Column> columns = new ArrayList<>();
        for (XmlElement column : element.children(COLUMN)) {
            Map<String, String> attributes = new LinkedHashMap<>(column.attributes());
            column.child(CONSTRAINTS).ifPresent(constraints -> attributes.putAll(constraints.attributes()));
            columns.add(new Column(attributes, column.line()));
        }
        return new Change(
                element.name(), element.attributes(), columns, element.childText(WHERE), element.text(),
                element.line(), false);
    }

    private List<Change> yamlChanges(TextFile file) {
        List<Change> changes = new ArrayList<>();
        for (YamlNode document : YamlParser.parse(file.lines()).orElse(List.of())) {
            for (YamlNode item : document.get(ROOT).items()) {
                for (YamlNode change : item.get(CHANGE_SET).get(CHANGES).items()) {
                    change.entries().forEach((name, body) -> changes.add(yamlChange(name, body)));
                }
            }
        }
        return changes;
    }

    private Change yamlChange(String name, YamlNode body) {
        List<Column> columns = new ArrayList<>();
        for (YamlNode item : body.get(COLUMNS).items()) {
            YamlNode column = item.get(COLUMN);
            Map<String, String> attributes = scalars(column);
            attributes.putAll(scalars(column.get(CONSTRAINTS)));
            columns.add(new Column(attributes, column.line()));
        }
        // "- sql: текст" и "- sql: {sql: текст}" - обе записи допустимы
        YamlNode sql = body.isScalar() ? body : body.get(SQL);
        return new Change(
                name, scalars(body), columns, body.get(WHERE).text(), sql.text(), sql.exists() ? sql.line() : body.line(),
                sql.text().contains("\n"));
    }

    private Map<String, String> scalars(YamlNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        node.entries().forEach((key, value) -> {
            if (value.isScalar()) {
                values.put(key, value.text());
            }
        });
        return values;
    }

    private void translate(Change change, List<SqlStatements.Statement> statements) {
        String table = change.get(TABLE_NAME);
        switch (change.name()) {
            case "dropTable" -> add(statements, change, "DROP TABLE " + table);
            case "dropColumn" -> dropColumns(change, table, statements);
            case "addColumn" -> addColumns(change, table, statements);
            case "createTable" -> add(statements, change, "CREATE TABLE " + table + " (" + definitions(change) + ")");
            case "createIndex" -> add(statements, change,
                    "CREATE " + (TRUE.equalsIgnoreCase(change.get(UNIQUE)) ? "UNIQUE " : "") + "INDEX "
                            + orUnnamed(change.get("indexName")) + " ON " + table + " (" + columnNames(change) + ")");
            case "addForeignKeyConstraint" -> add(statements, change,
                    "ALTER TABLE " + change.get("baseTableName") + " ADD CONSTRAINT "
                            + orUnnamed(change.get("constraintName")) + " FOREIGN KEY (" + change.get("baseColumnNames")
                            + ") REFERENCES " + change.get(REFERENCED_TABLE) + " (" + change.get("referencedColumnNames") + ")");
            case "addPrimaryKey" -> add(statements, change,
                    "ALTER TABLE " + table + " ADD CONSTRAINT " + orUnnamed(change.get("constraintName"))
                            + " PRIMARY KEY (" + change.get(COLUMN_NAMES) + ")");
            case "addUniqueConstraint" -> add(statements, change,
                    "ALTER TABLE " + table + " ADD CONSTRAINT " + orUnnamed(change.get("constraintName"))
                            + " UNIQUE (" + change.get(COLUMN_NAMES) + ")");
            case "update" -> add(statements, change, "UPDATE " + table + " SET " + columnNames(change) + whereOf(change));
            case "delete" -> add(statements, change, "DELETE FROM " + table + whereOf(change));
            case SQL -> embeddedSql(change, statements);
            default -> {
                // Остальные изменения (переименование, представления, последовательности) правила не проверяют
            }
        }
    }

    // Колонка задается атрибутом columnName либо вложенными column
    private void dropColumns(Change change, String table, List<SqlStatements.Statement> statements) {
        List<String> names = new ArrayList<>();
        if (!change.get(COLUMN_NAME).isEmpty()) {
            names.add(change.get(COLUMN_NAME));
        }
        change.columns().forEach(column -> names.add(column.get(NAME)));
        names.forEach(name -> add(statements, change, "ALTER TABLE " + table + " DROP COLUMN " + name));
    }

    private void addColumns(Change change, String table, List<SqlStatements.Statement> statements) {
        for (Column column : change.columns()) {
            String name = column.get(NAME);
            statements.add(new SqlStatements.Statement(
                    "ALTER TABLE " + table + " ADD COLUMN " + name + " " + typeOf(column)
                            + (FALSE.equalsIgnoreCase(column.get(NULLABLE)) ? " NOT NULL" : "")
                            + (column.hasValue() ? " DEFAULT value" : ""),
                    column.line()));
            if (column.references()) {
                add(statements, change, "ALTER TABLE " + table + " ADD CONSTRAINT "
                        + orUnnamed(column.get("foreignKeyName")) + " FOREIGN KEY (" + name + ") REFERENCES "
                        + column.referenced());
            }
            if (isKey(column)) {
                add(statements, change, "ALTER TABLE " + table + " ADD CONSTRAINT " + UNNAMED + " UNIQUE (" + name + ")");
            }
        }
    }

    // Определения колонок в том же виде, что и в CREATE TABLE
    private String definitions(Change change) {
        return change.columns().stream()
                .map(column -> column.get(NAME) + " " + typeOf(column)
                        + (TRUE.equalsIgnoreCase(column.get(PRIMARY_KEY)) ? " PRIMARY KEY" : "")
                        + (TRUE.equalsIgnoreCase(column.get(UNIQUE)) ? " UNIQUE" : "")
                        + (column.references() ? " REFERENCES " + column.referenced() : ""))
                .collect(Collectors.joining(", "));
    }

    private boolean isKey(Column column) {
        return TRUE.equalsIgnoreCase(column.get(PRIMARY_KEY)) || TRUE.equalsIgnoreCase(column.get(UNIQUE));
    }

    private String columnNames(Change change) {
        return change.columns().stream().map(column -> column.get(NAME)).collect(Collectors.joining(", "));
    }

    private String whereOf(Change change) {
        return change.where().isBlank() ? "" : " WHERE " + change.where().trim();
    }

    private String typeOf(Column column) {
        return column.get(TYPE).isEmpty() ? UNKNOWN_TYPE : column.get(TYPE);
    }

    private String orUnnamed(String name) {
        return name.isEmpty() ? UNNAMED : name;
    }

    // Текст <sql> разбирается как обычный SQL-файл; строки считаются от строки самого изменения
    private void embeddedSql(Change change, List<SqlStatements.Statement> statements) {
        int firstLine = change.multiline() ? change.line() + 1 : change.line();
        for (SqlStatements.Statement statement : SqlStatements.parse(List.of(change.sql().split("\n", -1)))) {
            statements.add(new SqlStatements.Statement(statement.text(), firstLine + statement.line() - 1));
        }
    }

    private void add(List<SqlStatements.Statement> statements, Change change, String sql) {
        statements.add(new SqlStatements.Statement(sql, change.line()));
    }
}
