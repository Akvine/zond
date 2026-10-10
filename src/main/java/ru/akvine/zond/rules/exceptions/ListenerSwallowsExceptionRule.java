package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class ListenerSwallowsExceptionRule extends AbstractRule {
    private static final Set<String> PRINT_METHODS = Set.of("printStackTrace", "println", "print", "printf");

    @Override
    public String code() {
        return RuleCodes.LISTENER_SWALLOWS_EXCEPTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет слушателей очередей, которые ловят любое исключение и только пишут его в лог";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration listener : Messaging.listeners(sourceFile.unit())) {
            // При ручном подтверждении проглоченная ошибка дает другую беду - сообщение зависает; о ней отдельное правило
            if (listener.getBody().isEmpty() || Messaging.acknowledgment(listener).isPresent()) {
                continue;
            }
            // Только try, который охватывает работу слушателя целиком: try вокруг одного элемента пачки
            // пропускает один элемент, а не сообщение
            for (Statement statement : listener.getBody().get().getStatements()) {
                if (!statement.isTryStmt()) {
                    continue;
                }
                for (CatchClause clause : statement.asTryStmt().getCatchClauses()) {
                    if (Messaging.catchesEverything(clause) && onlyLogs(clause)) {
                        violations.add(violation(sourceFile, clause,
                                "Слушатель '" + listener.getNameAsString() + "' ловит "
                                        + clause.getParameter().getType().asString() + " и только пишет в лог: брокер"
                                        + " считает сообщение обработанным, и оно пропадает без следа; пробросьте"
                                        + " исключение, чтобы сработали повторы и очередь ошибок, либо сохраните"
                                        + " сообщение для разбора"));
                    }
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

    // Отправка в очередь ошибок, запись в таблицу сбоев, nack - это обработка; здесь же ищем catch, где нет
    // ничего, кроме записи в лог
    private boolean onlyLogs(CatchClause clause) {
        return clause.getBody().getStatements().stream().allMatch(this::isLogging);
    }

    private boolean isLogging(Statement statement) {
        if (statement.isReturnStmt()) {
            return statement.asReturnStmt().getExpression().isEmpty();
        }
        if (!statement.isExpressionStmt()) {
            return false;
        }
        Expression expression = statement.asExpressionStmt().getExpression();
        if (!expression.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        return Loggers.isLogCall(call) || PRINT_METHODS.contains(call.getNameAsString());
    }
}
