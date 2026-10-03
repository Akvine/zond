package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BlockStmt;
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
public class CheckMisleadingIndentationRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_MISLEADING_INDENTATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет if и циклы без фигурных скобок, под которыми с отступом идет несколько строк";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (Statement statement : sourceFile.unit().findAll(Statement.class)) {
            Optional<Statement> body = findBody(statement);
            if (body.isEmpty() || body.get().isBlockStmt() || body.get().isEmptyStmt()) {
                continue;
            }

            // Следующая строка сдвинута так же, как тело: выглядит частью конструкции, но выполняется всегда
            findNext(statement)
                    .filter(next -> column(next) > column(statement) && column(next) == column(body.get()))
                    .ifPresent(next -> violations.add(violation(sourceFile, next,
                            "Строка с отступом под конструкцией без фигурных скобок: по виду она внутри условия"
                                    + " или цикла, а на деле выполняется всегда; добавьте фигурные скобки")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Последняя ветка: у if с else это else, иначе само тело
    private Optional<Statement> findBody(Node statement) {
        if (statement instanceof IfStmt ifStmt) {
            // У цепочки else if последнюю ветку проверим, когда дойдем до последнего if в ней
            if (ifStmt.getElseStmt().filter(Statement::isIfStmt).isPresent()) {
                return Optional.empty();
            }
            return Optional.of(ifStmt.getElseStmt().orElse(ifStmt.getThenStmt()));
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

    private Optional<Statement> findNext(Statement statement) {
        if (!(statement.getParentNode().orElse(null) instanceof BlockStmt block)) {
            return Optional.empty();
        }
        int next = block.getStatements().indexOf(statement) + 1;
        return next < block.getStatements().size() ? Optional.of(block.getStatement(next)) : Optional.empty();
    }

    private int column(Node node) {
        return node.getBegin().map(position -> position.column).orElse(0);
    }
}
