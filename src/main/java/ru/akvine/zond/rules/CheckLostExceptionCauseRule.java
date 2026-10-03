package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckLostExceptionCauseRule extends AbstractRule {
    // Способы привязать исходное исключение помимо конструктора
    private static final Set<String> CAUSE_METHODS = Set.of("initCause", "addSuppressed");

    @Override
    public String code() {
        return RuleCodes.CHECK_LOST_EXCEPTION_CAUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет в catch создание нового исключения без передачи исходного как причины";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (CatchClause clause : sourceFile.unit().findAll(CatchClause.class)) {
            String caught = clause.getParameter().getNameAsString();
            if (attachesCauseManually(clause, caught)) {
                continue;
            }

            for (ThrowStmt throwStmt : clause.getBody().findAll(ThrowStmt.class)) {
                // throw во вложенном catch относится к своему исключению, а в лямбде - к другой функции
                if (!throwStmt.getExpression().isObjectCreationExpr()
                        || nearestCatch(throwStmt) != clause
                        || Nodes.isInNestedScope(throwStmt, clause)) {
                    continue;
                }

                ObjectCreationExpr created = throwStmt.getExpression().asObjectCreationExpr();
                if (!passesCause(created, caught)) {
                    violations.add(violation(sourceFile, throwStmt,
                            "Новое исключение " + created.getType().getNameAsString() + " создается без исходного '"
                                    + caught + "': стек и причина первоначальной ошибки теряются, найти ее по логу"
                                    + " будет нельзя; передайте '" + caught + "' в конструктор как cause"));
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

    // new X("text", e): само исключение должно быть аргументом. e.getMessage() причину не сохраняет
    private boolean passesCause(ObjectCreationExpr created, String caught) {
        return created.getArguments().stream()
                .map(Nodes::unwrap)
                .anyMatch(argument -> argument.isNameExpr() && argument.asNameExpr().getNameAsString().equals(caught));
    }

    private boolean attachesCauseManually(CatchClause clause, String caught) {
        return clause.getBody().findAll(MethodCallExpr.class).stream()
                .filter(call -> CAUSE_METHODS.contains(call.getNameAsString()))
                .anyMatch(call -> call.getArguments().stream().anyMatch(argument -> argument.toString().equals(caught)));
    }

    private Node nearestCatch(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof CatchClause)) {
            current = current.getParentNode().orElse(null);
        }
        return current;
    }
}
