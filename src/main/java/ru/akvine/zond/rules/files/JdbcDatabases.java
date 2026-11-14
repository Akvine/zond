package ru.akvine.zond.rules.files;

import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.TextFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * С какими базами данных работает проект: по JDBC-адресам в настройках и в коде и по драйверам в файлах сборки
 */
@UtilityClass
public class JdbcDatabases {
    public static final String POSTGRES = "postgresql";
    public static final String MYSQL = "mysql";
    public static final String MARIADB = "mariadb";
    public static final String SQL_SERVER = "sqlserver";
    public static final String SYBASE = "sybase";
    public static final String SQLITE = "sqlite";
    public static final String ORACLE = "oracle";

    // jdbc:postgresql://host/db, jdbc:tc:mysql:8:///db, jdbc:jtds:sqlserver://host
    private static final Pattern URL = Pattern.compile("jdbc:(?:tc:)?(?:jtds:)?([a-z0-9]+):");

    // Драйвер в pom.xml или build.gradle -> база
    private static final Map<String, String> DRIVERS = Map.of(
            "org.postgresql", POSTGRES,
            "mysql-connector", MYSQL,
            "mariadb-java-client", MARIADB,
            "mssql-jdbc", SQL_SERVER,
            "jtds", SQL_SERVER,
            "jconn", SYBASE,
            "sqlite-jdbc", SQLITE,
            "ojdbc", ORACLE);

    /**
     * JDBC-адрес, найденный в проекте
     *
     * @param kind база из адреса: postgresql, mysql, sqlserver
     */
    public record Url(Path file, int line, String kind, String text) {
    }

    /**
     * @return JDBC-адреса из рабочих настроек и рабочего кода; адреса тестов и профилей разработки не считаются
     */
    public List<Url> urls(ScanContext context) {
        List<Url> urls = new ArrayList<>();
        for (ConfigFile config : context.configFiles()) {
            if (config.isNonProduction()) {
                continue;
            }
            for (ConfigProperty property : config.properties()) {
                add(urls, config.path(), property.line(), property.value());
            }
        }
        for (SourceFile source : context.sources()) {
            if (TestPaths.isTest(source.path())) {
                continue;
            }
            for (LiteralStringValueExpr literal : source.unit().findAll(LiteralStringValueExpr.class)) {
                if (literal.isStringLiteralExpr() || literal.isTextBlockLiteralExpr()) {
                    add(urls, source.path(), literal.getBegin().map(position -> position.line).orElse(1),
                            literal.getValue());
                }
            }
        }
        return urls;
    }

    /**
     * @return базы, с которыми работает проект; пусто, если по проекту этого не видно
     */
    public Set<String> of(ScanContext context) {
        Set<String> kinds = new LinkedHashSet<>();
        urls(context).forEach(url -> kinds.add(url.kind()));
        for (TextFile file : context.textFiles()) {
            if (!TextFiles.isPom(file) && !TextFiles.isGradle(file)) {
                continue;
            }
            for (String line : file.lines()) {
                DRIVERS.forEach((driver, kind) -> {
                    if (line.contains(driver)) {
                        kinds.add(kind);
                    }
                });
            }
        }
        return kinds;
    }

    private void add(List<Url> urls, Path file, int line, String text) {
        if (text == null) {
            return;
        }
        Matcher matcher = URL.matcher(text);
        if (matcher.find()) {
            urls.add(new Url(file, line, matcher.group(1), text));
        }
    }
}
