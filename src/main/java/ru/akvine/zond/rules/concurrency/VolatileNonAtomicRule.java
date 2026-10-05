package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class VolatileNonAtomicRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.VOLATILE_NON_ATOMIC_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет неатомарные операции над volatile-полями: ++, +=, x = x + 1";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (UnaryExpr unary : sourceFile.unit().findAll(UnaryExpr.class)) {
            boolean changes = unary.getOperator().name().endsWith("INCREMENT")
                    || unary.getOperator().name().endsWith("DECREMENT");
            if (changes && isUnprotectedVolatile(unary.getExpression(), unary)) {
                violations.add(report(sourceFile, unary));
            }
        }

        for (AssignExpr assign : sourceFile.unit().findAll(AssignExpr.class)) {
            // x += 1 либо x = x + 1: новое значение зависит от старого
            boolean readsItself = assign.getOperator() != AssignExpr.Operator.ASSIGN
                    || assign.getValue().findAll(NameExpr.class).stream()
                    .anyMatch(name -> name.toString().equals(fieldName(assign.getTarget())));
            if (readsItself && isUnprotectedVolatile(assign.getTarget(), assign)) {
                violations.add(report(sourceFile, assign));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private Violation report(SourceFile sourceFile, Expression operation) {
        return violation(sourceFile, operation,
                "Неатомарная операция '" + operation + "' над volatile-полем: volatile гарантирует видимость"
                        + " значения, но чтение, изменение и запись - три отдельных шага, два потока потеряют"
                        + " одно из обновлений; используйте AtomicInteger / AtomicLong либо синхронизацию");
    }

    private boolean isUnprotectedVolatile(Expression target, Node operation) {
        boolean isVolatile = LocalTypes.findDeclaration(target)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof FieldDeclaration field && field.isVolatile())
                .isPresent();
        return isVolatile && !isSynchronized(operation);
    }

    private boolean isSynchronized(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof SynchronizedStmt
                    || (current instanceof MethodDeclaration method && method.isSynchronized())) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private String fieldName(Expression target) {
        return target.isFieldAccessExpr() ? target.asFieldAccessExpr().getNameAsString() : target.toString();
    }
}
