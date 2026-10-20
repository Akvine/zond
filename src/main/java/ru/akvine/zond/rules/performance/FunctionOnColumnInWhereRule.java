package ru.akvine.zond.rules.performance;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.DbSchema;
import ru.akvine.zond.rules.support.QueryTexts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Функция над колонкой в условии: WHERE LOWER(email) = ?, DATE(created_at) = ?. Обычный индекс хранит значения
 * колонки как есть, и для такого сравнения база его не использует - читает таблицу целиком. Если в миграциях
 * есть индекс по выражению (CREATE INDEX ... ON users (lower(email))), находки нет.
 */
@Component
public class FunctionOnColumnInWhereRule extends AbstractContextRule {
    private static final Pattern WHERE = Pattern.compile("(?i)\\bwhere\\b|<where>");
    // lower(u.email) = ..., date(created_at) between ..., cast(created as date) = ...
    private static final Pattern FUNCTION_ON_COLUMN = Pattern.compile(
            "(?i)\\b(lower|upper|trim|ltrim|rtrim|date|year|month|day|cast|to_char|to_date|trunc|date_format"
                    + "|substring|substr|left|right|abs|round)\\s*\\(\\s*"
                    + "([a-z_]\\w*(?:\\.[a-z_]\\w*)?)\\s*(?:,[^()]*)?(?:\\s+as\\s+\\w+(?:\\s*\\(\\s*\\d+\\s*\\))?)?\\)"
                    + "\\s*(=|<>|!=|<=|>=|<|>|\\blike\\b|\\bin\\b|\\bbetween\\b)");
    private static final String LIKE = "like";
    private static final String CAST = "cast";
    // '%x', concat('%', :x), lower(concat('%', ...)), %:x, %?
    private static final Pattern LEADING_WILDCARD = Pattern.compile(
            "(?i)\\s*((lower|upper)\\s*\\(\\s*)?('%|concat\\s*\\(\\s*'%'|%\\s*[:?])");
    // CREATE INDEX ... (lower(email)), (lower((email)::text)), ON users USING btree (date(created_at))
    private static final Pattern CREATE_INDEX = Pattern.compile("(?i)\\bcreate\\s+(unique\\s+)?index\\b");
    private static final Pattern INDEXED_EXPRESSION = Pattern.compile(
            "(?i)(\\w+)\\s*\\(\\s*\\(?\\s*\"?([a-z_]\\w*)\"?\\s*(?:\\)|::|,)");
    // Ключевые слова и литералы, которые регулярное выражение может принять за колонку
    private static final Set<String> NOT_COLUMNS = Set.of("null", "true", "false", "current_date", "current_timestamp", "now");

    @Override
    public String code() {
        return RuleCodes.FUNCTION_ON_COLUMN_IN_WHERE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует запросы в коде и мапперах MyBatis и ищет функции над колонкой в условии WHERE"
                + " (LOWER(email) = ?), из-за которых индекс по колонке не используется";
    }

    // Есть ли индекс по колонке, по таблицам не разобрать: запрос в строке, а схема - в миграциях
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Set<String> expressionIndexes = expressionIndexes(DbSchema.migrations(context));
        List<Violation> violations = new ArrayList<>();
        for (QueryTexts.QueryText query : QueryTexts.of(context)) {
            findFunctionOnColumn(query.text(), expressionIndexes).ifPresent(found -> violations.add(violation(
                    query.file(), query.line(),
                    "Функция '" + found + "' над колонкой в условии: обычный индекс хранит значения колонки как есть,"
                            + " и для такого сравнения база его не использует - читает таблицу целиком; сравнивайте"
                            + " колонку без функции (храните данные уже в нужном виде, для дат - диапазон"
                            + " created_at >= :from AND created_at < :to) либо создайте индекс по выражению")));
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

    private Optional<String> findFunctionOnColumn(String text, Set<String> expressionIndexes) {
        Matcher where = WHERE.matcher(text);
        if (!where.find()) {
            return Optional.empty();
        }
        Matcher matcher = FUNCTION_ON_COLUMN.matcher(text.substring(where.end()));
        while (matcher.find()) {
            String function = matcher.group(1).toLowerCase(Locale.ROOT);
            String qualified = matcher.group(2).toLowerCase(Locale.ROOT);
            String column = qualified.substring(qualified.lastIndexOf('.') + 1);
            if (NOT_COLUMNS.contains(column) || expressionIndexes.contains(function + "|" + column)
                    || isSubstringSearch(matcher, text.substring(where.end()), function)) {
                continue;
            }
            return Optional.of(matcher.group(1) + "(" + matcher.group(2) + ")");
        }
        return Optional.empty();
    }

    // LOWER(name) LIKE '%x%', CAST(config AS text) LIKE ...: поиск подстроки индекс не использует и без функции,
    // о шаблоне с % в начале сообщает jr:234
    private boolean isSubstringSearch(Matcher matcher, String condition, String function) {
        if (!LIKE.equalsIgnoreCase(matcher.group(3))) {
            return false;
        }
        return CAST.equals(function) || LEADING_WILDCARD.matcher(condition.substring(matcher.end())).lookingAt();
    }

    // lower|email для CREATE INDEX ... (lower(email)); оператор может занимать несколько строк
    private Set<String> expressionIndexes(List<TextFile> migrations) {
        Set<String> indexes = new HashSet<>();
        for (TextFile migration : migrations) {
            String text = String.join(" ", migration.lines());
            Matcher create = CREATE_INDEX.matcher(text);
            while (create.find()) {
                int end = text.indexOf(';', create.end());
                String statement = text.substring(create.end(), end < 0 ? text.length() : end);
                Matcher expression = INDEXED_EXPRESSION.matcher(statement);
                while (expression.find()) {
                    indexes.add(expression.group(1).toLowerCase(Locale.ROOT) + "|" + expression.group(2).toLowerCase(Locale.ROOT));
                }
            }
        }
        return indexes;
    }
}
