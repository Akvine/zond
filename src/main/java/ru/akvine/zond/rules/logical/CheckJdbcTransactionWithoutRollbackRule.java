package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Optional;

@Component
public class CheckJdbcTransactionWithoutRollbackRule extends AbstractRule {
    private static final String SET_AUTO_COMMIT = "setAutoCommit";
    private static final String ROLLBACK = "rollback";
    private static final String FALSE = "false";

    @Override
    public String code() {
        return RuleCodes.CHECK_JDBC_TRANSACTION_WITHOUT_ROLLBACK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ручные JDBC-транзакции (setAutoCommit(false)) без rollback";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> SET_AUTO_COMMIT.equals(call.getNameAsString())
                        && call.getArguments().size() == 1
                        && FALSE.equals(call.getArgument(0).toString()))
                .filter(call -> !hasRollback(call) && !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "Транзакция начата через setAutoCommit(false), а rollback() в методе нет: при ошибке"
                                + " посередине соединение вернется в пул с незавершенной транзакцией, и ее"
                                + " изменения зафиксирует или потеряет следующий, кто его получит; вызывайте"
                                + " rollback() в catch"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean hasRollback(MethodCallExpr call) {
        Optional<Node> callable = Nodes.enclosingCallable(call);
        return callable.isPresent() && callable.get().findAll(MethodCallExpr.class).stream()
                .anyMatch(other -> ROLLBACK.equals(other.getNameAsString()));
    }
}
