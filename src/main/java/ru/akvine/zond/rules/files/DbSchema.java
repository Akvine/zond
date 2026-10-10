package ru.akvine.zond.rules.files;

import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Схема базы данных, какой она получается после всех миграций проекта: таблицы, их колонки, первичные ключи,
 * уникальные ограничения и индексы.
 * Миграции (SQL и журналы Liquibase) применяются по порядку имен файлов. Разбор намеренно терпимый:
 * команда, которую понять не удалось, пропускается, а таблица с неизвестным составом помечается как неясная.
 */
public final class DbSchema {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^create\\s+((?:global\\s+|local\\s+)?(?:temporary|temp)\\s+|unlogged\\s+)?table\\s+"
                    + "(?:if\\s+not\\s+exists\\s+)?([^\\s(]+)\\s*(.*)$", FLAGS);
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^alter\\s+table\\s+(?:only\\s+)?(?:if\\s+exists\\s+)?(\\S+)\\s+(.*)$", FLAGS);
    private static final Pattern DROP_TABLE = Pattern.compile(
            "^drop\\s+table\\s+(?:if\\s+exists\\s+)?(.+?)(?:\\s+(?:cascade|restrict))?$", FLAGS);
    private static final Pattern RENAME_TABLE = Pattern.compile("^rename\\s+table\\s+(\\S+)\\s+to\\s+(\\S+)$", FLAGS);
    // create [unique] [bitmap] index [concurrently] [if not exists] [имя] on [only] таблица [using btree] (колонки)
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "^create\\s+(unique\\s+)?(?:(?!index\\b)\\w+\\s+)?index\\s+(?:concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?"
                    + "(?:(?!on\\s)(\\S+)\\s+)?on\\s+(?:only\\s+)?([^\\s(]+)\\s*(?:using\\s+\\w+\\s*)?(\\(.*)$", FLAGS);
    // index idx_name (a, b), key idx_name (a) - обычный индекс, объявленный вместе с таблицей либо в alter table ... add
    private static final int INDEX_UNIQUE = 1;
    private static final int INDEX_NAME = 2;
    private static final int INDEX_TABLE = 3;
    private static final int INDEX_COLUMNS = 4;
    private static final Pattern PLAIN_INDEX = Pattern.compile(
            "^(?:add\\s+)?(?:index|key)\\s+(?:(?!\\()([\\w\"`]+)\\s*)?\\(([^)]*)\\)", FLAGS);
    // drop index [concurrently] [if exists] имя [on таблица]
    private static final Pattern DROP_INDEX = Pattern.compile(
            "^drop\\s+index\\s+(?:concurrently\\s+)?(?:if\\s+exists\\s+)?(\\S+?)(?:\\s+on\\s+(\\S+))?"
                    + "(?:\\s+(?:cascade|restrict))?$", FLAGS);
    private static final Pattern DROP_CONSTRAINT = Pattern.compile(
            "^drop\\s+(?:constraint|index|key)\\s+(?:if\\s+exists\\s+)?(\\S+).*$", FLAGS);
    private static final Pattern CONSTRAINT_NAME = Pattern.compile("\\bconstraint\\s+(\\S+)\\s+(?:unique|primary)\\b", FLAGS);
    private static final String UNKNOWN_TABLE = "";
    // unique (a, b), unique key uq_name (a), primary key (a, b) - отдельным ограничением таблицы
    private static final Pattern UNIQUE_KEY = Pattern.compile(
            "\\b(?:unique(?:\\s+(?:key|index))?(?:\\s+(?!key\\b|index\\b)[\\w\"`]+)?|primary\\s+key)\\s*\\(([^)]*)\\)", FLAGS);
    private static final Pattern UNIQUE = Pattern.compile("\\bunique\\b", FLAGS);
    private static final Pattern IDENTIFIER = Pattern.compile("[\\w\"`]+");
    private static final String COLUMNS_DELIMITER = ",";

    private static final Pattern CONSTRAINT_START = Pattern.compile(
            "^(constraint|primary|foreign|unique|check|index|key|exclude|like)\\b.*", FLAGS);
    private static final Pattern PRIMARY_KEY = Pattern.compile("\\bprimary\\s+key\\b", FLAGS);
    private static final Pattern FOREIGN_KEY = Pattern.compile("\\bforeign\\s+key\\s*\\(([^)]*)\\)", FLAGS);
    private static final Pattern REFERENCES = Pattern.compile("\\breferences\\b", FLAGS);
    private static final Pattern NOT_NULL = Pattern.compile("\\bnot\\s+null\\b", FLAGS);

    // Где в определении колонки заканчивается тип и начинаются ограничения
    private static final Pattern TYPE_END = Pattern.compile(
            "\\s+(?:not\\s+null|null|default|primary|references|unique|check|constraint|generated|auto_increment"
                    + "|collate|comment|on\\s+update)\\b", FLAGS);
    private static final Pattern CHAR_LENGTH = Pattern.compile(
            "^(?:n?var)?char\\w*(?:\\s+varying)?\\s*\\(\\s*(\\d{1,9})(?:\\s+\\w+)?\\s*\\)", FLAGS);

    private static final Pattern ADD_CONSTRAINT = Pattern.compile(
            "^add\\s+(?:constraint\\s+\\S+\\s+)?(?:primary\\s+key|foreign\\s+key|unique|check|index|key|exclude)\\b.*", FLAGS);
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "^add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?(\\S+)\\s+(.+)$", FLAGS);
    private static final Pattern DROP_COLUMN = Pattern.compile(
            "^drop\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?(\\S+).*$", FLAGS);
    private static final Pattern RENAME_TO = Pattern.compile("^rename\\s+to\\s+(\\S+)$", FLAGS);
    private static final Pattern RENAME_COLUMN = Pattern.compile(
            "^rename\\s+(?:column\\s+)?(\\S+)\\s+to\\s+(\\S+)$", FLAGS);
    private static final Pattern ALTER_TYPE = Pattern.compile(
            "^alter\\s+(?:column\\s+)?(\\S+)\\s+(?:set\\s+data\\s+)?type\\s+(.+)$", FLAGS);
    private static final Pattern SET_NOT_NULL = Pattern.compile(
            "^alter\\s+(?:column\\s+)?(\\S+)\\s+set\\s+not\\s+null$", FLAGS);
    private static final Pattern DROP_NOT_NULL = Pattern.compile(
            "^alter\\s+(?:column\\s+)?(\\S+)\\s+drop\\s+not\\s+null$", FLAGS);
    private static final Pattern MODIFY = Pattern.compile("^modify\\s+(?:column\\s+)?(\\S+)\\s+(.+)$", FLAGS);
    private static final Pattern NULLABILITY = Pattern.compile("^(not\\s+)?null$", FLAGS);
    private static final Pattern CHANGE = Pattern.compile("^change\\s+(?:column\\s+)?(\\S+)\\s+(\\S+)\\s+(.+)$", FLAGS);

    // После drop / rename стоит не имя колонки, а вид объекта: ограничение, индекс, ключ
    private static final Set<String> NOT_COLUMNS = Set.of(
            "constraint", "index", "primary", "foreign", "key", "check", "default", "not", "identity", "partition");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    public enum Kind {
        CREATE_TABLE, DROP_TABLE, RENAME_TABLE, ADD_COLUMN, DROP_COLUMN, RENAME_COLUMN, CHANGE_TYPE,
        SET_NOT_NULL, DROP_NOT_NULL, ADD_PRIMARY_KEY, ADD_FOREIGN_KEY, ADD_UNIQUE, ADD_INDEX, DROP_KEY
    }

    /**
     * @param length длина строкового типа (varchar(50) -> 50) либо null, если она не задана или тип не строковый
     */
    public record Column(String name, String type, Integer length, boolean notNull, boolean references, Path file, int line) {
    }

    /**
     * Одно изменение схемы, прочитанное из команды SQL
     *
     * @param column  колонка, к которой относится изменение; для CREATE_TABLE - null, для ADD_UNIQUE и ADD_INDEX -
     *                колонки ключа через запятую, в том порядке, в каком они в нем стоят
     * @param table   таблица; у DROP_KEY может быть пустой: индекс удаляют по имени, не называя таблицу
     * @param value   новое имя либо новый тип
     * @param columns колонки создаваемой таблицы
     * @param opaque  состав таблицы по команде не виден: create table ... as select
     */
    public record Change(
            Kind kind, String table, String column, String value, List<Column> columns, boolean primaryKey,
            boolean opaque) {

        private static Change of(Kind kind, String table, String column, String value) {
            return new Change(kind, table, column, value, List.of(), false, false);
        }
    }

    /**
     * Ограничение или индекс таблицы
     *
     * @param name    имя: по нему их удаляют; пустое, если имя не задано
     * @param columns колонки в том порядке, в каком они стоят в ключе, в виде, пригодном для сравнения
     * @param unique  значения в этих колонках не повторяются: уникальное ограничение, уникальный индекс, первичный ключ
     */
    private record Key(String name, List<String> columns, boolean unique) {
    }

    public static final class Table {
        private final String name;
        private final Path file;
        private final int line;
        private final Map<String, Column> columns = new LinkedHashMap<>();
        private final Set<String> foreignKeys = new LinkedHashSet<>();
        // Уникальные ограничения, первичный ключ и индексы
        private final List<Key> keys = new ArrayList<>();
        private boolean primaryKey;
        private boolean opaque;

        private Table(String name, Path file, int line) {
            this.name = name;
            this.file = file;
            this.line = line;
        }

        public String name() {
            return name;
        }

        public Path file() {
            return file;
        }

        public int line() {
            return line;
        }

        public Collection<Column> columns() {
            return columns.values();
        }

        public boolean hasPrimaryKey() {
            return primaryKey;
        }

        /**
         * @return true, если состав таблицы по миграциям установить не удалось: сверять с ней код нельзя
         */
        public boolean isOpaque() {
            return opaque;
        }

        /**
         * @return true для таблицы-связки: каждая ее колонка - внешний ключ
         */
        public boolean isLinkTable() {
            return columns.size() > 1 && columns.values().stream()
                    .allMatch(column -> column.references() || foreignKeys.contains(column.name()));
        }

        /**
         * @return true, если строки с одинаковыми значениями в этих колонках база не примет: на них (или на часть
         * из них) есть уникальное ограничение, уникальный индекс либо первичный ключ
         */
        public boolean isUnique(Collection<String> columnNames) {
            Set<String> wanted = new LinkedHashSet<>();
            columnNames.forEach(column -> wanted.add(loose(column)));
            return keys.stream().filter(Key::unique).anyMatch(key -> wanted.containsAll(key.columns()));
        }

        /**
         * @return true, если поиску по этим колонкам поможет индекс: он начинается с одной из них. Индекс, в котором
         * колонка стоит второй или дальше, для поиска только по ней бесполезен
         */
        public boolean hasIndexOn(Collection<String> columnNames) {
            Set<String> wanted = new LinkedHashSet<>();
            columnNames.forEach(column -> wanted.add(loose(column)));
            return keys.stream().anyMatch(key -> wanted.contains(key.columns().get(0)));
        }

        /**
         * Имя сверяется без учета регистра и подчеркиваний: created_at, createdAt и CREATEDAT - одна колонка.
         * Так сверка не зависит от стратегии именования Hibernate.
         */
        public Optional<Column> find(String columnName) {
            String wanted = loose(columnName);
            return columns.values().stream().filter(column -> loose(column.name()).equals(wanted)).findFirst();
        }
    }

    private final Map<String, Table> tables = new LinkedHashMap<>();

    private DbSchema() {
    }

    public static DbSchema of(ScanContext context) {
        DbSchema schema = new DbSchema();
        for (TextFile file : migrations(context)) {
            for (SqlStatements.Statement statement : SqlStatements.of(file)) {
                for (Change change : changes(statement.text())) {
                    schema.apply(change, file.path(), statement.line());
                }
            }
        }
        return schema;
    }

    /**
     * @return миграции в порядке применения: V2__x.sql идет раньше V10__y.sql
     */
    public static List<TextFile> migrations(ScanContext context) {
        return context.textFiles().stream()
                .filter(TextFiles::isMigration)
                .sorted(Comparator.comparing((TextFile file) -> file.path().toString(), DbSchema::compareNaturally))
                .toList();
    }

    public boolean isEmpty() {
        return tables.isEmpty();
    }

    public Collection<Table> tables() {
        return tables.values();
    }

    public Optional<Table> find(String tableName) {
        String wanted = loose(name(tableName));
        return tables.values().stream().filter(table -> loose(table.name()).equals(wanted)).findFirst();
    }

    /**
     * @return изменения схемы, которые делает команда; пустой список, если команда схему не меняет либо не понята
     */
    public static List<Change> changes(String statement) {
        Matcher create = CREATE_TABLE.matcher(statement);
        if (create.matches()) {
            // Временная таблица живет до конца сеанса: частью схемы она не становится
            if (create.group(1) != null && !create.group(1).toLowerCase(Locale.ROOT).startsWith("unlogged")) {
                return List.of();
            }
            String created = name(create.group(2));
            List<Change> changes = new ArrayList<>();
            changes.add(createTable(created, create.group(3)));
            keysOf(create.group(3)).forEach(key -> changes.add(Change.of(
                    key.unique() ? Kind.ADD_UNIQUE : Kind.ADD_INDEX, created, key.columns(), key.name())));
            return changes;
        }
        Matcher index = CREATE_INDEX.matcher(statement);
        if (index.matches()) {
            String columns = index.group(INDEX_COLUMNS);
            int end = closingParenthesis(columns);
            return end < 0
                    ? List.of()
                    : List.of(Change.of(index.group(INDEX_UNIQUE) == null ? Kind.ADD_INDEX : Kind.ADD_UNIQUE,
                            name(index.group(INDEX_TABLE)), keyColumns(columns.substring(1, end)),
                            index.group(INDEX_NAME) == null ? null : name(index.group(INDEX_NAME))));
        }
        Matcher dropIndex = DROP_INDEX.matcher(statement);
        if (dropIndex.matches()) {
            return List.of(Change.of(Kind.DROP_KEY,
                    dropIndex.group(2) == null ? UNKNOWN_TABLE : name(dropIndex.group(2)), null, name(dropIndex.group(1))));
        }
        Matcher drop = DROP_TABLE.matcher(statement);
        if (drop.matches()) {
            List<Change> changes = new ArrayList<>();
            for (String table : drop.group(1).split(",")) {
                changes.add(Change.of(Kind.DROP_TABLE, name(table.trim()), null, null));
            }
            return changes;
        }
        Matcher rename = RENAME_TABLE.matcher(statement);
        if (rename.matches()) {
            return List.of(Change.of(Kind.RENAME_TABLE, name(rename.group(1)), null, name(rename.group(2))));
        }
        Matcher alter = ALTER_TABLE.matcher(statement);
        if (!alter.matches()) {
            return List.of();
        }
        String table = name(alter.group(1));
        List<Change> changes = new ArrayList<>();
        for (String action : splitTopLevel(alter.group(2))) {
            alterAction(table, action.trim(), changes);
        }
        return changes;
    }

    /**
     * @return имя без кавычек и схемы, в нижнем регистре: "public"."Orders" -> orders
     */
    public static String name(String raw) {
        String cleaned = raw.replaceAll("[\"`\\[\\]]", "").toLowerCase(Locale.ROOT);
        return cleaned.substring(cleaned.lastIndexOf('.') + 1);
    }

    private static String loose(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }

    private static Change createTable(String table, String rest) {
        if (!rest.startsWith("(")) {
            return new Change(Kind.CREATE_TABLE, table, null, null, List.of(), false, true);
        }
        int end = closingParenthesis(rest);
        if (end < 0) {
            return new Change(Kind.CREATE_TABLE, table, null, null, List.of(), false, true);
        }

        List<Column> columns = new ArrayList<>();
        Set<String> referencing = new LinkedHashSet<>();
        boolean primaryKey = false;
        for (String part : splitTopLevel(rest.substring(1, end))) {
            String definition = part.trim();
            if (definition.isEmpty()) {
                continue;
            }
            if (CONSTRAINT_START.matcher(definition).matches()) {
                primaryKey |= PRIMARY_KEY.matcher(definition).find();
                referencing.addAll(foreignKeyColumns(definition));
                continue;
            }
            Column column = column(definition);
            if (column != null) {
                primaryKey |= PRIMARY_KEY.matcher(definition).find();
                columns.add(column);
            }
        }
        // Внешний ключ, объявленный отдельным ограничением, переносим на саму колонку
        List<Column> resolved = columns.stream()
                .map(column -> referencing.contains(column.name()) ? withReference(column) : column)
                .toList();
        return new Change(Kind.CREATE_TABLE, table, null, null, resolved, primaryKey, false);
    }

    // Ключи создаваемой таблицы: объявленные у колонки, отдельным ограничением и обычные индексы
    private static List<DeclaredKey> keysOf(String rest) {
        List<DeclaredKey> keys = new ArrayList<>();
        int end = rest.startsWith("(") ? closingParenthesis(rest) : -1;
        if (end < 0) {
            return keys;
        }
        for (String part : splitTopLevel(rest.substring(1, end))) {
            String definition = part.trim();
            if (definition.isEmpty()) {
                continue;
            }
            if (CONSTRAINT_START.matcher(definition).matches()) {
                tableKey(definition).ifPresent(keys::add);
            } else if (UNIQUE.matcher(definition).find() || PRIMARY_KEY.matcher(definition).find()) {
                keys.add(new DeclaredKey(constraintName(definition), keyColumns(definition.split("\\s+")[0]), true));
            }
        }
        return keys;
    }

    // Ключ, объявленный отдельной строкой таблицы: unique (a, b), primary key (a), index idx (a)
    private static Optional<DeclaredKey> tableKey(String definition) {
        Matcher unique = UNIQUE_KEY.matcher(definition);
        if (unique.find()) {
            return Optional.of(new DeclaredKey(constraintName(definition), keyColumns(unique.group(1)), true));
        }
        Matcher plain = PLAIN_INDEX.matcher(definition);
        if (!plain.find()) {
            return Optional.empty();
        }
        String name = plain.group(1) == null ? null : name(plain.group(1));
        return Optional.of(new DeclaredKey(name, keyColumns(plain.group(2)), false));
    }

    /**
     * @param name    имя ограничения или индекса либо null, если оно не задано
     * @param columns колонки ключа через запятую
     * @param unique  ключ уникальный
     */
    private record DeclaredKey(String name, String columns, boolean unique) {
    }

    // constraint uq_email unique (email) -> uq_email
    private static String constraintName(String definition) {
        Matcher constraint = CONSTRAINT_NAME.matcher(definition);
        return constraint.find() ? name(constraint.group(1)) : null;
    }

    // email, lower(email), email desc -> email: из выражения индекса берется сама колонка
    private static String keyColumns(String list) {
        List<String> columns = new ArrayList<>();
        for (String part : splitTopLevel(list)) {
            String item = part.trim();
            int open = item.indexOf('(');
            Matcher identifier = IDENTIFIER.matcher(open < 0 ? item : item.substring(open + 1));
            if (identifier.find()) {
                columns.add(name(identifier.group()));
            }
        }
        return String.join(COLUMNS_DELIMITER, columns);
    }

    private static void alterAction(String table, String action, List<Change> changes) {
        if (ADD_CONSTRAINT.matcher(action).matches()) {
            Matcher key = UNIQUE_KEY.matcher(action);
            Matcher plain = PLAIN_INDEX.matcher(action);
            if (key.find()) {
                changes.add(Change.of(Kind.ADD_UNIQUE, table, keyColumns(key.group(1)), constraintName(action)));
            } else if (plain.find()) {
                changes.add(Change.of(Kind.ADD_INDEX, table, keyColumns(plain.group(2)),
                        plain.group(1) == null ? null : name(plain.group(1))));
            }
            if (PRIMARY_KEY.matcher(action).find()) {
                changes.add(Change.of(Kind.ADD_PRIMARY_KEY, table, null, null));
            }
            foreignKeyColumns(action).forEach(column -> changes.add(Change.of(Kind.ADD_FOREIGN_KEY, table, column, null)));
            return;
        }
        Matcher dropConstraint = DROP_CONSTRAINT.matcher(action);
        if (dropConstraint.matches()) {
            changes.add(Change.of(Kind.DROP_KEY, table, null, name(dropConstraint.group(1))));
            return;
        }
        Matcher renameTo = RENAME_TO.matcher(action);
        if (renameTo.matches()) {
            changes.add(Change.of(Kind.RENAME_TABLE, table, null, name(renameTo.group(1))));
            return;
        }
        Matcher renameColumn = RENAME_COLUMN.matcher(action);
        if (renameColumn.matches()) {
            if (!NOT_COLUMNS.contains(renameColumn.group(1).toLowerCase(Locale.ROOT))) {
                changes.add(Change.of(Kind.RENAME_COLUMN, table, name(renameColumn.group(1)), name(renameColumn.group(2))));
            }
            return;
        }
        Matcher setNotNull = SET_NOT_NULL.matcher(action);
        if (setNotNull.matches()) {
            changes.add(Change.of(Kind.SET_NOT_NULL, table, name(setNotNull.group(1)), null));
            return;
        }
        Matcher dropNotNull = DROP_NOT_NULL.matcher(action);
        if (dropNotNull.matches()) {
            changes.add(Change.of(Kind.DROP_NOT_NULL, table, name(dropNotNull.group(1)), null));
            return;
        }
        Matcher type = ALTER_TYPE.matcher(action);
        if (type.matches()) {
            changes.add(Change.of(Kind.CHANGE_TYPE, table, name(type.group(1)), typeOf(type.group(2))));
            return;
        }
        Matcher modify = MODIFY.matcher(action);
        if (modify.matches()) {
            // modify col null / not null (Oracle, MySQL) меняет только обязательность, тип остается прежним
            Matcher nullability = NULLABILITY.matcher(modify.group(2).trim());
            if (nullability.matches()) {
                Kind kind = nullability.group(1) == null ? Kind.DROP_NOT_NULL : Kind.SET_NOT_NULL;
                changes.add(Change.of(kind, table, name(modify.group(1)), null));
            } else {
                changes.add(Change.of(Kind.CHANGE_TYPE, table, name(modify.group(1)), typeOf(modify.group(2))));
            }
            return;
        }
        Matcher change = CHANGE.matcher(action);
        if (change.matches()) {
            String from = name(change.group(1));
            String to = name(change.group(2));
            if (!from.equals(to)) {
                changes.add(Change.of(Kind.RENAME_COLUMN, table, from, to));
            }
            changes.add(Change.of(Kind.CHANGE_TYPE, table, to, typeOf(change.group(3))));
            return;
        }
        Matcher add = ADD_COLUMN.matcher(action);
        if (add.matches()) {
            Column column = column(add.group(1) + " " + add.group(2));
            if (column != null) {
                changes.add(new Change(Kind.ADD_COLUMN, table, column.name(), null, List.of(column),
                        PRIMARY_KEY.matcher(action).find(), false));
                if (UNIQUE.matcher(action).find() || PRIMARY_KEY.matcher(action).find()) {
                    changes.add(Change.of(Kind.ADD_UNIQUE, table, column.name(), null));
                }
            }
            return;
        }
        Matcher drop = DROP_COLUMN.matcher(action);
        if (drop.matches() && !NOT_COLUMNS.contains(drop.group(1).toLowerCase(Locale.ROOT))) {
            changes.add(Change.of(Kind.DROP_COLUMN, table, name(drop.group(1)), null));
        }
    }

    // id bigint not null primary key
    private static Column column(String definition) {
        int space = definition.indexOf(' ');
        if (space < 0) {
            return null;
        }
        String rest = definition.substring(space + 1).trim();
        String type = typeOf(rest);
        Matcher length = CHAR_LENGTH.matcher(type);
        return new Column(
                name(definition.substring(0, space)),
                type,
                length.find() ? Integer.valueOf(length.group(1)) : null,
                NOT_NULL.matcher(rest).find() || PRIMARY_KEY.matcher(rest).find(),
                REFERENCES.matcher(rest).find(),
                null,
                0);
    }

    private static String typeOf(String definition) {
        Matcher end = TYPE_END.matcher(" " + definition);
        String type = end.find() ? definition.substring(0, Math.max(0, end.start() - 1)) : definition;
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> foreignKeyColumns(String text) {
        List<String> columns = new ArrayList<>();
        Matcher foreignKey = FOREIGN_KEY.matcher(text);
        while (foreignKey.find()) {
            for (String column : foreignKey.group(1).split(",")) {
                columns.add(name(column.trim()));
            }
        }
        return columns;
    }

    private static Column withReference(Column column) {
        return new Column(column.name(), column.type(), column.length(), column.notNull(), true, column.file(), column.line());
    }

    private void apply(Change change, Path file, int line) {
        Table table = tables.get(change.table());
        switch (change.kind()) {
            case CREATE_TABLE -> {
                // create table if not exists для уже известной таблицы ее не меняет
                if (table == null) {
                    Table created = new Table(change.table(), file, line);
                    created.opaque = change.opaque();
                    created.primaryKey = change.primaryKey();
                    change.columns().forEach(column -> created.columns.put(column.name(), placed(column, file, line)));
                    tables.put(change.table(), created);
                }
            }
            case DROP_TABLE -> tables.remove(change.table());
            // Индекс удаляют по имени, не называя таблицу: ищем его во всех
            case DROP_KEY -> (table == null ? tables.values() : List.of(table))
                    .forEach(found -> found.keys.removeIf(key -> change.value().equals(key.name())));
            case RENAME_TABLE -> {
                if (table != null) {
                    tables.remove(change.table());
                    Table renamed = new Table(change.value(), table.file, table.line);
                    renamed.columns.putAll(table.columns);
                    renamed.foreignKeys.addAll(table.foreignKeys);
                    renamed.keys.addAll(table.keys);
                    renamed.primaryKey = table.primaryKey;
                    renamed.opaque = table.opaque;
                    tables.put(change.value(), renamed);
                }
            }
            default -> {
                if (table != null) {
                    applyToTable(table, change, file, line);
                }
            }
        }
    }

    private void applyToTable(Table table, Change change, Path file, int line) {
        Column column = change.column() == null ? null : table.columns.get(change.column());
        switch (change.kind()) {
            case ADD_COLUMN -> {
                change.columns().forEach(added -> table.columns.putIfAbsent(added.name(), placed(added, file, line)));
                table.primaryKey |= change.primaryKey();
            }
            case DROP_COLUMN -> {
                table.columns.remove(change.column());
                // Вместе с колонкой исчезают ограничение и индекс, в которые она входила
                table.keys.removeIf(key -> key.columns().contains(loose(change.column())));
            }
            case ADD_UNIQUE, ADD_INDEX -> {
                // Набор с порядком вставки: колонка в ключе одна, а ее место в нем важно
                Set<String> key = new LinkedHashSet<>();
                for (String name : change.column().split(COLUMNS_DELIMITER)) {
                    if (!name.isBlank()) {
                        key.add(loose(name));
                    }
                }
                if (!key.isEmpty()) {
                    table.keys.add(new Key(change.value() == null ? "" : change.value(), new ArrayList<>(key),
                            change.kind() == Kind.ADD_UNIQUE));
                }
            }
            case ADD_PRIMARY_KEY -> table.primaryKey = true;
            case ADD_FOREIGN_KEY -> table.foreignKeys.add(change.column());
            case RENAME_COLUMN -> {
                if (column != null) {
                    table.columns.remove(change.column());
                    table.columns.put(change.value(), new Column(change.value(), column.type(), column.length(),
                            column.notNull(), column.references(), column.file(), column.line()));
                    // Место колонки в ключе при переименовании сохраняется: от него зависит, поможет ли индекс
                    for (Key key : table.keys) {
                        key.columns().replaceAll(name -> name.equals(loose(change.column())) ? loose(change.value()) : name);
                    }
                }
            }
            case CHANGE_TYPE -> {
                if (column != null) {
                    Matcher length = CHAR_LENGTH.matcher(change.value());
                    table.columns.put(column.name(), new Column(column.name(), change.value(),
                            length.find() ? Integer.valueOf(length.group(1)) : null,
                            column.notNull(), column.references(), column.file(), column.line()));
                }
            }
            case SET_NOT_NULL, DROP_NOT_NULL -> {
                if (column != null) {
                    table.columns.put(column.name(), new Column(column.name(), column.type(), column.length(),
                            change.kind() == Kind.SET_NOT_NULL, column.references(), column.file(), column.line()));
                }
            }
            default -> {
                // остальные изменения относятся к таблице целиком и обработаны выше
            }
        }
    }

    private Column placed(Column column, Path file, int line) {
        return new Column(column.name(), column.type(), column.length(), column.notNull(), column.references(), file, line);
    }

    // Запятые внутри скобок (numeric(10, 2), primary key (a, b)) части не разделяют
    private static List<String> splitTopLevel(String body) {
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

    // Позиция скобки, закрывающей ту, с которой начинается текст
    private static int closingParenthesis(String text) {
        int depth = 0;
        for (int index = 0; index < text.length(); index++) {
            char symbol = text.charAt(index);
            if (symbol == '(') {
                depth++;
            } else if (symbol == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    // Числа внутри имен сравниваются как числа: иначе V10 оказалась бы раньше V2
    private static int compareNaturally(String left, String right) {
        Matcher leftNumbers = NUMBER.matcher(left);
        Matcher rightNumbers = NUMBER.matcher(right);
        int leftPosition = 0;
        int rightPosition = 0;
        while (leftNumbers.find() && rightNumbers.find()) {
            int text = left.substring(leftPosition, leftNumbers.start())
                    .compareTo(right.substring(rightPosition, rightNumbers.start()));
            if (text != 0) {
                return text;
            }
            String leftNumber = leftNumbers.group().replaceFirst("^0+(?=\\d)", "");
            String rightNumber = rightNumbers.group().replaceFirst("^0+(?=\\d)", "");
            int number = leftNumber.length() != rightNumber.length()
                    ? Integer.compare(leftNumber.length(), rightNumber.length())
                    : leftNumber.compareTo(rightNumber);
            if (number != 0) {
                return number;
            }
            leftPosition = leftNumbers.end();
            rightPosition = rightNumbers.end();
        }
        return left.substring(leftPosition).compareTo(right.substring(rightPosition));
    }
}
