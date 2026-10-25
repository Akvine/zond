package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Для какой базы написан SQL-файл, если это видно по пути: activiti.mysql.upgradestep.sql, db/oracle/V1__init.sql,
 * schema-postgresql.sql. Проекты, которые поддерживают несколько баз, держат для каждой свой набор скриптов
 */
@UtilityClass
public class SqlDialects {
    public static final String POSTGRES = "postgres";

    private static final Map<String, String> DIALECTS = Map.ofEntries(
            Map.entry("postgres", POSTGRES), Map.entry("postgresql", POSTGRES), Map.entry("pg", POSTGRES),
            Map.entry("pgsql", POSTGRES), Map.entry("mysql", "mysql"), Map.entry("mariadb", "mysql"),
            Map.entry("oracle", "oracle"), Map.entry("mssql", "mssql"), Map.entry("sqlserver", "mssql"),
            Map.entry("db2", "db2"), Map.entry("h2", "h2"), Map.entry("hsql", "hsql"), Map.entry("hsqldb", "hsql"),
            Map.entry("derby", "derby"), Map.entry("sqlite", "sqlite"));
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[._\\-]");

    /**
     * @return база, для которой написан файл; пусто, если по пути этого не понять
     */
    public Optional<String> of(Path path) {
        for (Path part : path) {
            for (String word : WORD_SEPARATOR.split(part.toString().toLowerCase(Locale.ROOT))) {
                String dialect = DIALECTS.get(word);
                if (dialect != null) {
                    return Optional.of(dialect);
                }
            }
        }
        return Optional.empty();
    }
}
