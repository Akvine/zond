package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ManualResourceCloseRule extends AbstractRule {
    private static final String CLOSE = "close";

    @Override
    public String code() {
        return RuleCodes.MANUAL_RESOURCE_CLOSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ресурсы, которые закрываются вручную вне finally вместо try-with-resources";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            if (!Resources.isLocalVariable(variable)
                    || variable.getInitializer().filter(Resources::opens).isEmpty()) {
                continue;
            }

            // Ресурс, который вообще не закрывается, ловит отдельное правило.
            // Закрытие в finally безопасно, хотя try-with-resources короче
            List<MethodCallExpr> closes = findCloseCalls(variable);
            if (!closes.isEmpty() && closes.stream().noneMatch(this::isInFinally)) {
                violations.add(violation(sourceFile, variable,
                        "Ресурс '" + variable.getNameAsString() + "' закрывается вручную вне finally: если до"
                                + " close() возникнет исключение, ресурс останется открытым;"
                                + " используйте try-with-resources"));
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
        return ErrorType.RESOURCE;
    }

    private List<MethodCallExpr> findCloseCalls(VariableDeclarator variable) {
        Optional<Node> callable = Nodes.enclosingCallable(variable);
        if (callable.isEmpty()) {
            return List.of();
        }
        return callable.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> CLOSE.equals(call.getNameAsString()))
                .filter(call -> call.getScope()
                        .filter(scope -> scope.isNameExpr()
                                && scope.asNameExpr().getNameAsString().equals(variable.getNameAsString()))
                        .isPresent())
                .toList();
    }

    private boolean isInFinally(Node node) {
        Node child = node;
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            Node block = child;
            if (current instanceof TryStmt tryStmt && tryStmt.getFinallyBlock().filter(body -> body == block).isPresent()) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
