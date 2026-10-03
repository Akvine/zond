package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class CheckRedundantBooleanReturnRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_REDUNDANT_BOOLEAN_RETURN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ветвление, которое возвращает true и false вместо самого условия";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        // if (x) { return true; } else { return false; }
        for (IfStmt ifStmt : sourceFile.unit().findAll(IfStmt.class)) {
            Optional<Boolean> thenValue = returnedLiteral(ifStmt.getThenStmt());
            Optional<Boolean> elseValue = ifStmt.getElseStmt().flatMap(this::returnedLiteral);
            if (thenValue.isPresent() && elseValue.isPresent() && !thenValue.get().equals(elseValue.get())) {
                violations.add(report(sourceFile, ifStmt, ifStmt.getCondition(), thenValue.get()));
            }
        }

        // x ? true : false
        for (ConditionalExpr ternary : sourceFile.unit().findAll(ConditionalExpr.class)) {
            Optional<Boolean> thenValue = literal(ternary.getThenExpr());
            Optional<Boolean> elseValue = literal(ternary.getElseExpr());
            if (thenValue.isPresent() && elseValue.isPresent() && !thenValue.get().equals(elseValue.get())) {
                violations.add(report(sourceFile, ternary, ternary.getCondition(), thenValue.get()));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private Violation report(SourceFile sourceFile, Node node, Expression condition, boolean direct) {
        String replacement = direct ? condition.toString() : "!(" + condition + ")";
        return violation(sourceFile, node,
                "Ветвление возвращает true и false вместо самого условия: это то же самое, что '" + replacement
                        + "', только длиннее; верните условие напрямую");
    }

    // return true; либо { return true; }
    private Optional<Boolean> returnedLiteral(Statement statement) {
        Statement single = statement;
        if (statement.isBlockStmt() && statement.asBlockStmt().getStatements().size() == 1) {
            single = statement.asBlockStmt().getStatement(0);
        }
        if (!single.isReturnStmt()) {
            return Optional.empty();
        }
        return single.asReturnStmt().getExpression().flatMap(this::literal);
    }

    private Optional<Boolean> literal(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value.isBooleanLiteralExpr() ? Optional.of(value.asBooleanLiteralExpr().getValue()) : Optional.empty();
    }
}
