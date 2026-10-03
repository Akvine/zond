package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckMissingBatchProcessingRule extends AbstractRule {
    private static final String UPDATE = "update";
    private static final String EXECUTE_UPDATE = "executeUpdate";

    // Типы не разрешаем, поэтому JdbcTemplate и EntityManager узнаем по имени объекта
    private static final Pattern JDBC_TEMPLATE = Pattern.compile(".*jdbctemplate.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY_MANAGER =
            Pattern.compile("^(em|session)$|.*entitymanager.*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ENTITY_MANAGER_WRITES = Set.of("persist", "merge");

    @Override
    public String code() {
        return RuleCodes.CHECK_MISSING_BATCH_PROCESSING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись в БД по одному элементу в цикле через JdbcTemplate, Statement"
                + " и EntityManager";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Вызовы репозиториев в цикле ловит отдельное правило
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(Loops::isRepeated)
                .flatMap(call -> findAdvice(call)
                        .map(advice -> violation(sourceFile, call,
                                "'" + call.getScope().get() + "." + call.getNameAsString() + "(...)' в цикле:"
                                        + " каждая запись уходит в БД отдельным запросом; " + advice))
                        .stream())
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    /**
     * @return как сделать то же пакетно, если вызов - поэлементная запись в БД
     */
    private Optional<String> findAdvice(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }

        String receiver = MethodCalls.receiverName(call.getScope().get());
        String method = call.getNameAsString();
        if (UPDATE.equals(method) && JDBC_TEMPLATE.matcher(receiver).matches()) {
            return Optional.of("используйте batchUpdate(...)");
        }
        if (EXECUTE_UPDATE.equals(method) && !Repositories.isRepository(receiver)) {
            return Optional.of("используйте addBatch() и executeBatch()");
        }
        if (ENTITY_MANAGER_WRITES.contains(method) && ENTITY_MANAGER.matcher(receiver).matches()) {
            return Optional.of("включите hibernate.jdbc.batch_size и делайте flush() / clear() пачками");
        }
        return Optional.empty();
    }
}
