package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class LogAndRethrowRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.LOG_AND_RETHROW_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет catch, который записывает исключение в лог и тут же пробрасывает его дальше";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (CatchClause clause : sourceFile.unit().findAll(CatchClause.class)) {
            List<Statement> statements = clause.getBody().getStatements();
            if (statements.isEmpty() || !statements.get(statements.size() - 1).isThrowStmt()) {
                continue;
            }

            // Исключение записано в лог и уходит выше, где его запишут еще раз
            String caught = clause.getParameter().getNameAsString();
            findLogOf(clause, caught).ifPresent(logCall -> violations.add(violation(sourceFile, logCall,
                    "Исключение '" + caught + "' записывается в лог и пробрасывается дальше: его запишет еще и тот,"
                            + " кто поймает выше, - одна ошибка даст несколько записей со стеком; либо обработайте"
                            + " исключение здесь, либо только пробросьте")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }

    // log.error(..., e) уровня info и выше, где среди аргументов есть пойманное исключение
    private Optional<MethodCallExpr> findLogOf(CatchClause clause, String caught) {
        return clause.getBody().findAll(MethodCallExpr.class).stream()
                .filter(call -> Loggers.isLogCall(call) && Loggers.isEnabledLevel(call))
                .filter(call -> call.getArguments().stream()
                        .flatMap(argument -> argument.findAll(NameExpr.class).stream())
                        .anyMatch(name -> name.getNameAsString().equals(caught)))
                .findFirst();
    }
}
