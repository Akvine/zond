package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Messaging;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectWords;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class KafkaSendResultIgnoredRule extends AbstractRule implements ProjectRule {
    // Свой слушатель отправки получает каждую ошибку, а внутри транзакции она всплывет при коммите
    private static final Set<String> HANDLED_ELSEWHERE = Set.of("ProducerListener", "setProducerListener");
    private static final String IN_TRANSACTION = "executeInTransaction";

    @Override
    public String code() {
        return RuleCodes.KAFKA_SEND_RESULT_IGNORED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет отправку в Kafka, результат которой никто не проверяет";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Violation> violations = new ArrayList<>();
        if (ProjectWords.hasAny(sourceFiles, HANDLED_ELSEWHERE)) {
            return violations;
        }
        for (SourceFile sourceFile : sourceFiles) {
            for (ExpressionStmt statement : sourceFile.unit().findAll(ExpressionStmt.class)) {
                Expression expression = Nodes.unwrap(statement.getExpression());
                if (!expression.isMethodCallExpr()) {
                    continue;
                }
                MethodCallExpr call = expression.asMethodCallExpr();
                if (Messaging.isKafkaSend(call) && !TestClasses.isInside(call) && !isInTransaction(call)) {
                    violations.add(violation(sourceFile, call,
                            "Результат '" + Messaging.describeSend(call).orElse(call.getNameAsString()) + "(...)'"
                                    + " не используется: отправка в Kafka не ждет ответа брокера, поэтому метод"
                                    + " завершится успешно, даже если сообщение не доставлено, - ошибка попадет"
                                    + " только в лог, а код продолжит работу так, будто отправка удалась;"
                                    + " обработайте результат (whenComplete) либо дождитесь его (get с таймаутом)"));
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
        return ErrorType.EXCEPTION;
    }

    private boolean isInTransaction(MethodCallExpr call) {
        return call.findAncestor(MethodCallExpr.class, outer -> IN_TRANSACTION.equals(outer.getNameAsString())).isPresent();
    }
}
