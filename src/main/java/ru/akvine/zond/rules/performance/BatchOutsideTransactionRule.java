package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.JdbcDatabases;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.JdbcBatches;
import ru.akvine.zond.rules.support.TransactionScopes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Пакет выполняется вне транзакции, то есть в режиме автофиксации. MySQL, MariaDB, SQL Server и SQLite в этом
 * режиме фиксируют каждую строку пакета отдельно: журнал сбрасывается на диск столько раз, сколько строк.
 * В одной транзакции сброс один. PostgreSQL и Oracle выполняют пакет одной неявной транзакцией, для них разницы
 * в скорости нет - правило молчит, когда проект работает только с ними.
 */
@Component
public class BatchOutsideTransactionRule extends AbstractContextRule {
    // Базы, которые в режиме автофиксации фиксируют каждую строку пакета
    private static final Set<String> COMMIT_PER_ROW = Set.of(
            JdbcDatabases.MYSQL, JdbcDatabases.MARIADB, JdbcDatabases.SQL_SERVER, JdbcDatabases.SYBASE,
            JdbcDatabases.SQLITE);
    private static final String GET_CONNECTION = "getConnection";

    private static final String TEMPLATE_ADVICE = "выполняйте пакет в одной транзакции - @Transactional"
            + " или TransactionTemplate";
    private static final String CONNECTION_ADVICE = "вызовите setAutoCommit(false) до пакета и commit() после";

    @Override
    public String code() {
        return RuleCodes.BATCH_OUTSIDE_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет batchUpdate и executeBatch вне транзакции: в режиме автофиксации MySQL,"
                + " SQL Server и SQLite фиксируют каждую строку пакета отдельно";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Set<String> databases = JdbcDatabases.of(context);
        boolean commitsPerRow = databases.stream().anyMatch(COMMIT_PER_ROW::contains);
        // Проект работает только с базами, которым транзакция пакет не ускоряет
        if (!databases.isEmpty() && !commitsPerRow) {
            return List.of();
        }

        CallGraph graph = CallGraph.of(context.sources());
        List<Violation> violations = new ArrayList<>();
        for (SourceFile source : context.sources()) {
            if (TestPaths.isTest(source.path())) {
                continue;
            }
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                Optional<String> advice = findAdvice(call);
                if (advice.isEmpty() || CodeContexts.isStartup(call)
                        || TransactionScopes.isInTransaction(call, graph)) {
                    continue;
                }
                Violation violation = violation(source.path(),
                        call.getBegin().map(position -> position.line).orElse(1),
                        "'" + call.getScope().get() + "." + call.getNameAsString() + "(...)' выполняется вне"
                                + " транзакции: в режиме автофиксации MySQL, SQL Server и SQLite фиксируют каждую"
                                + " строку пакета отдельно, и журнал сбрасывается на диск столько раз, сколько"
                                + " строк; " + advice.get());
                // С какой базой работает проект, не видно: PostgreSQL и Oracle транзакция пакет не ускорит
                violations.add(commitsPerRow ? violation : violation.withConfidence(Confidence.SUSPICION));
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

    /**
     * @return как выполнить пакет в транзакции, если вызов - пакетная запись
     */
    private Optional<String> findAdvice(MethodCallExpr call) {
        if (JdbcBatches.isBatchUpdate(call)) {
            return Optional.of(TEMPLATE_ADVICE);
        }
        if (!JdbcBatches.isExecuteBatch(call)) {
            return Optional.empty();
        }
        // Режим соединения известен, только когда метод сам берет его из источника данных: соединение,
        // полученное параметром, мог настроить вызывающий код
        boolean opensConnection = call.findAncestor(MethodDeclaration.class)
                .filter(method -> method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(other -> GET_CONNECTION.equals(other.getNameAsString())))
                .isPresent();
        return opensConnection ? Optional.of(CONNECTION_ADVICE) : Optional.empty();
    }
}
