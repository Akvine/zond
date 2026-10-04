package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class CheckDoubleCheckedLockingRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_DOUBLE_CHECKED_LOCKING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет double-checked locking по полю без volatile";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (IfStmt outer : sourceFile.unit().findAll(IfStmt.class)) {
            Optional<String> checked = nullCheckedName(outer.getCondition());
            if (checked.isEmpty() || !hasSecondCheckUnderLock(outer, checked.get())) {
                continue;
            }

            LocalTypes.findField(outer, checked.get())
                    .flatMap(this::fieldOf)
                    .filter(field -> !field.isVolatile())
                    .ifPresent(field -> violations.add(violation(sourceFile, outer,
                            "Double-checked locking по полю '" + checked.get() + "' без volatile:"
                                    + " другой поток может увидеть не до конца созданный объект;"
                                    + " объявите поле volatile")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    // if (x == null) { synchronized (...) { if (x == null) { ... } } }
    private boolean hasSecondCheckUnderLock(IfStmt outer, String name) {
        return outer.getThenStmt().findAll(SynchronizedStmt.class).stream()
                .flatMap(lock -> lock.getBody().findAll(IfStmt.class).stream())
                .anyMatch(inner -> nullCheckedName(inner.getCondition()).filter(name::equals).isPresent());
    }

    // x == null, this.x == null, null == x
    private Optional<String> nullCheckedName(Expression condition) {
        Expression value = Nodes.unwrap(condition);
        if (!value.isBinaryExpr() || value.asBinaryExpr().getOperator() != BinaryExpr.Operator.EQUALS) {
            return Optional.empty();
        }

        Expression left = Nodes.unwrap(value.asBinaryExpr().getLeft());
        Expression right = Nodes.unwrap(value.asBinaryExpr().getRight());
        if (left.isNullLiteralExpr()) {
            return name(right);
        }
        return right.isNullLiteralExpr() ? name(left) : Optional.empty();
    }

    private Optional<String> name(Expression expression) {
        if (expression.isNameExpr()) {
            return Optional.of(expression.asNameExpr().getNameAsString());
        }
        if (expression.isFieldAccessExpr()) {
            return Optional.of(expression.asFieldAccessExpr().getNameAsString());
        }
        return Optional.empty();
    }

    private Optional<FieldDeclaration> fieldOf(VariableDeclarator variable) {
        return variable.getParentNode()
                .filter(parent -> parent instanceof FieldDeclaration)
                .map(parent -> (FieldDeclaration) parent);
    }
}
