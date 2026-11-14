package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Пакетная запись через JDBC: {@code jdbcTemplate.batchUpdate(...)} и {@code statement.executeBatch()}
 */
@UtilityClass
public class JdbcBatches {
    private static final String BATCH_UPDATE = "batchUpdate";
    private static final String ADD_BATCH = "addBatch";
    private static final Set<String> EXECUTE_BATCH = Set.of("executeBatch", "executeLargeBatch");

    // JdbcTemplate узнаем по типу, а если тип определить не удалось - по имени объекта
    private static final Set<String> JDBC_TEMPLATE_TYPES = Set.of(
            "JdbcTemplate", "JdbcOperations", "NamedParameterJdbcTemplate", "NamedParameterJdbcOperations");
    private static final Set<String> NAMED_TEMPLATE_TYPES = Set.of(
            "NamedParameterJdbcTemplate", "NamedParameterJdbcOperations");
    private static final Pattern JDBC_TEMPLATE_NAME =
            Pattern.compile(".*jdbc(template|operations)?$|.*jdbctemplate.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_TEMPLATE_NAME =
            Pattern.compile(".*named.*jdbc.*|.*jdbc.*named.*", Pattern.CASE_INSENSITIVE);

    /**
     * @return true для объекта JdbcTemplate либо NamedParameterJdbcTemplate
     */
    public boolean isJdbcTemplate(Expression scope) {
        return LocalTypes.isAnyOf(scope, JDBC_TEMPLATE_TYPES,
                () -> JDBC_TEMPLATE_NAME.matcher(MethodCalls.receiverName(scope)).matches());
    }

    /**
     * @return true для объекта NamedParameterJdbcTemplate: параметры запроса у него заданы именами
     */
    public boolean isNamedTemplate(Expression scope) {
        return LocalTypes.isAnyOf(scope, NAMED_TEMPLATE_TYPES,
                () -> NAMED_TEMPLATE_NAME.matcher(MethodCalls.receiverName(scope)).matches());
    }

    /**
     * @return true для вызова jdbcTemplate.batchUpdate(...)
     */
    public boolean isBatchUpdate(MethodCallExpr call) {
        return BATCH_UPDATE.equals(call.getNameAsString())
                && call.getScope().filter(JdbcBatches::isJdbcTemplate).isPresent();
    }

    /**
     * @return true для вызова statement.executeBatch()
     */
    public boolean isExecuteBatch(MethodCallExpr call) {
        return EXECUTE_BATCH.contains(call.getNameAsString()) && call.getArguments().isEmpty()
                && call.getScope().isPresent();
    }

    /**
     * @return true, если рабочий код проекта пишет в базу пакетами через JDBC
     */
    public boolean isUsedIn(List<SourceFile> sources) {
        return sources.stream()
                .filter(source -> !TestPaths.isTest(source.path()))
                .anyMatch(source -> source.unit().findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> BATCH_UPDATE.equals(call.getNameAsString())
                                || ADD_BATCH.equals(call.getNameAsString())));
    }
}
