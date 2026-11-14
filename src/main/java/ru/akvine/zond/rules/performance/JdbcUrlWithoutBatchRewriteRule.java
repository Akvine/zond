package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.JdbcDatabases;
import ru.akvine.zond.rules.support.JdbcBatches;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Проект пишет в базу пакетами, а в JDBC-адресе нет параметра, с которым драйвер отправляет пакет одним запросом.
 * Без него {@code executeBatch()} у PostgreSQL и MySQL экономит только на разборе запроса: строки по-прежнему
 * уходят по одной команде на строку.
 */
@Component
public class JdbcUrlWithoutBatchRewriteRule extends AbstractContextRule {
    /**
     * @param name    параметр драйвера
     * @param effect  что он меняет
     * @param limited true, если режим подходит не для всех таблиц и запросов
     */
    private record Option(String name, String effect, boolean limited) {
    }

    private static final Map<String, Option> OPTIONS = Map.of(
            JdbcDatabases.POSTGRES, new Option("reWriteBatchedInserts",
                    "с ним драйвер склеивает строки пакета в многострочные INSERT, и вставка идет в 2-3 раза"
                            + " быстрее", false),
            JdbcDatabases.MYSQL, new Option("rewriteBatchedStatements",
                    "без него драйвер отправляет каждую строку пакета отдельным запросом, с ним - одним"
                            + " многострочным INSERT", false),
            JdbcDatabases.SQL_SERVER, new Option("useBulkCopyForBatchInsert",
                    "с ним драйвер выполняет пакет простых INSERT массовой загрузкой (bulk copy); у режима есть"
                            + " ограничения по типам колонок и виду запроса - проверьте их на своих таблицах", true));

    // spring.jpa.properties.hibernate.jdbc.batch_size=50
    private static final String HIBERNATE_BATCH_SIZE = "jdbc.batch_size";
    private static final Pattern BATCH_OF_MANY = Pattern.compile("\\s*([2-9]|\\d{2,})\\s*");

    @Override
    public String code() {
        return RuleCodes.JDBC_URL_WITHOUT_BATCH_REWRITE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует настройки и код и ищет JDBC-адрес PostgreSQL, MySQL или SQL Server без параметра пакетной"
                + " вставки (reWriteBatchedInserts, rewriteBatchedStatements, useBulkCopyForBatchInsert) в проекте,"
                + " который пишет в базу пакетами";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        if (!writesInBatches(context)) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        Set<String> configured = new HashSet<>();
        for (JdbcDatabases.Url url : JdbcDatabases.urls(context)) {
            Option option = OPTIONS.get(url.kind());
            // Один адрес на файл: остальные адреса той же базы в нем повторяют ту же находку
            if (option == null || !reported.add(url.file() + ":" + url.kind())) {
                continue;
            }
            if (configured.contains(option.name()) || isMentioned(context, option.name())) {
                configured.add(option.name());
                continue;
            }
            Violation violation = violation(url.file(), url.line(),
                    "JDBC-адрес без параметра " + option.name() + "=true, а проект пишет в базу пакетами: "
                            + option.effect());
            violations.add(option.limited() ? violation.withConfidence(Confidence.SUSPICION) : violation);
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

    // batchUpdate / addBatch в коде либо пакеты Hibernate в настройках
    private boolean writesInBatches(ScanContext context) {
        if (JdbcBatches.isUsedIn(context.sources())) {
            return true;
        }
        return context.configFiles().stream()
                .flatMap(config -> config.properties().stream())
                .anyMatch(property -> property.key().replace('-', '_').contains(HIBERNATE_BATCH_SIZE)
                        && property.value() != null && BATCH_OF_MANY.matcher(property.value()).matches());
    }

    // Параметр задают не только в адресе: свойством источника данных в настройках либо из кода
    private boolean isMentioned(ScanContext context, String option) {
        String expected = option.toLowerCase(Locale.ROOT);
        boolean inConfigs = context.configFiles().stream()
                .flatMap(config -> config.properties().stream())
                .anyMatch(property -> contains(property.key(), expected) || contains(property.value(), expected));
        boolean inTextFiles = context.textFiles().stream()
                .anyMatch(file -> file.lines().stream().anyMatch(line -> contains(line, expected)));
        return inConfigs || inTextFiles || context.sources().stream()
                .anyMatch(source -> source.unit().findAll(LiteralStringValueExpr.class).stream()
                        .anyMatch(literal -> contains(literal.getValue(), expected)));
    }

    private boolean contains(String text, String expected) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(expected);
    }
}
