package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class CheckEmptyStatementRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_EMPTY_STATEMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет точку с запятой сразу после условия или заголовка цикла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (Statement statement : sourceFile.unit().findAll(Statement.class)) {
            findBody(statement)
                    .filter(Statement::isEmptyStmt)
                    .ifPresent(body -> violations.add(violation(sourceFile, statement,
                            "Точка с запятой сразу после " + keyword(statement) + ": телом конструкции стал пустой"
                                    + " оператор, а блок ниже выполняется всегда и один раз; уберите ';'")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private Optional<Statement> findBody(Node statement) {
        if (statement instanceof IfStmt ifStmt) {
            return Optional.of(ifStmt.getThenStmt());
        }
        if (statement instanceof ForStmt loop) {
            return Optional.of(loop.getBody());
        }
        if (statement instanceof ForEachStmt loop) {
            return Optional.of(loop.getBody());
        }
        if (statement instanceof WhileStmt loop) {
            return Optional.of(loop.getBody());
        }
        return Optional.empty();
    }

    private String keyword(Node statement) {
        if (statement instanceof IfStmt) {
            return "if (...)";
        }
        return statement instanceof WhileStmt ? "while (...)" : "for (...)";
    }
}
