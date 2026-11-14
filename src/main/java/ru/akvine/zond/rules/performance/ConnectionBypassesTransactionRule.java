package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TransactionScopes;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code dataSource.getConnection()} внутри транзакции Spring возвращает не соединение транзакции, а второе -
 * новое из пула. Запросы на нем фиксируются сразу и не откатываются вместе с транзакцией, а ее собственное
 * соединение все это время занято впустую: на каждый вызов уходит два соединения пула и лишние BEGIN и COMMIT.
 * Соединение транзакции отдает {@code DataSourceUtils.getConnection(dataSource)}; через него же работает
 * JdbcTemplate.
 */
@Component
public class ConnectionBypassesTransactionRule extends AbstractRule implements ProjectRule {
    private static final String GET_CONNECTION = "getConnection";
    private static final String GET_DATA_SOURCE = "getDataSource";
    private static final Set<String> DATA_SOURCE_TYPES = Set.of(
            "DataSource", "HikariDataSource", "BasicDataSource", "ComboPooledDataSource", "PooledDataSource",
            "DriverManagerDataSource", "AbstractRoutingDataSource");
    private static final Pattern DATA_SOURCE_NAME = Pattern.compile(".*datasource$|^ds$", Pattern.CASE_INSENSITIVE);
    // Такой источник данных сам отдает соединение текущей транзакции
    private static final String TRANSACTION_AWARE_PROXY = "TransactionAwareDataSourceProxy";
    // Транзакции в методе нет: вызывающая приостановлена
    private static final Set<String> SUSPENDING = Set.of("NOT_SUPPORTED", "NEVER");

    @Override
    public String code() {
        return RuleCodes.CONNECTION_BYPASSES_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет dataSource.getConnection() внутри @Transactional и TransactionTemplate:"
                + " из пула берется второе соединение, и работа на нем идет мимо транзакции";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        boolean usesAwareProxy = sourceFiles.stream()
                .anyMatch(source -> source.unit().findAll(ClassOrInterfaceType.class).stream()
                        .anyMatch(type -> TRANSACTION_AWARE_PROXY.equals(type.getNameAsString())));
        if (usesAwareProxy) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            if (TestPaths.isTest(sourceFile.path())) {
                continue;
            }
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (!GET_CONNECTION.equals(call.getNameAsString()) || !call.getArguments().isEmpty()
                        || call.getScope().isEmpty()) {
                    continue;
                }
                Expression source = Nodes.unwrap(call.getScope().get());
                if (isApplicationDataSource(source) && isInOwnTransaction(call)) {
                    violations.add(violation(sourceFile, call,
                            "'" + Nodes.text(call) + "' внутри транзакции берет из пула второе соединение: запросы"
                                    + " на нем идут мимо транзакции - фиксируются сразу и не откатываются вместе"
                                    + " с ней, а соединение транзакции все это время занято впустую; берите"
                                    + " соединение транзакции через DataSourceUtils.getConnection(dataSource) либо"
                                    + " работайте через JdbcTemplate"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Источник данных приложения: поле либо jdbcTemplate.getDataSource(). Источник, переданный параметром или
    // созданный в методе, - чаще другая база, и транзакция приложения к ней отношения не имеет
    private boolean isApplicationDataSource(Expression source) {
        if (source.isMethodCallExpr()) {
            return GET_DATA_SOURCE.equals(source.asMethodCallExpr().getNameAsString())
                    && source.asMethodCallExpr().getArguments().isEmpty();
        }
        if (source.isNameExpr() && LocalTypes.isLocal(source, source.asNameExpr().getNameAsString())) {
            return false;
        }
        if (!source.isNameExpr() && !(source.isFieldAccessExpr() && source.asFieldAccessExpr().getScope().isThisExpr())) {
            return false;
        }
        return LocalTypes.isAnyOf(source, DATA_SOURCE_TYPES,
                () -> DATA_SOURCE_NAME.matcher(MethodCalls.receiverName(source)).matches());
    }

    // Транзакцию открывает сам метод: аннотацией либо обратным вызовом TransactionTemplate вокруг вызова
    private boolean isInOwnTransaction(MethodCallExpr call) {
        if (TransactionScopes.enclosingCallback(call).isPresent()) {
            return true;
        }
        Optional<AnnotationExpr> annotation = call.findAncestor(MethodDeclaration.class)
                .flatMap(TransactionalAnnotations::findEffective);
        return annotation.filter(found -> SUSPENDING.stream().noneMatch(found.toString()::contains)).isPresent();
    }
}
