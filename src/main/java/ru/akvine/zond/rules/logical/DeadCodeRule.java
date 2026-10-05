package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class DeadCodeRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.DEAD_CODE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет код, который никогда не выполнится: после return / throw и под условием-константой";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        // Оператор после return / throw / break / continue в том же блоке
        for (BlockStmt block : sourceFile.unit().findAll(BlockStmt.class)) {
            List<Statement> statements = block.getStatements();
            for (int index = 0; index < statements.size() - 1; index++) {
                if (isJump(statements.get(index))) {
                    violations.add(violation(sourceFile, statements.get(index + 1),
                            "Недостижимый код: оператор стоит после безусловного выхода и никогда не выполнится;"
                                    + " удалите его либо исправьте порядок"));
                    break;
                }
            }
        }

        // if (false) { ... }, if (true) { ... } else { ... }, while (false) { ... }
        for (IfStmt ifStmt : sourceFile.unit().findAll(IfStmt.class)) {
            if (isConstant(ifStmt.getCondition())) {
                violations.add(reportConstantCondition(sourceFile, ifStmt, ifStmt.getCondition()));
            }
        }
        for (WhileStmt loop : sourceFile.unit().findAll(WhileStmt.class)) {
            // while (true) - обычный бесконечный цикл, его не трогаем
            if (isConstant(loop.getCondition()) && !loop.getCondition().asBooleanLiteralExpr().getValue()) {
                violations.add(reportConstantCondition(sourceFile, loop, loop.getCondition()));
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
        return ErrorType.LOGICAL;
    }

    private Violation reportConstantCondition(SourceFile sourceFile, Statement statement, Expression condition) {
        return violation(sourceFile, statement,
                "Условие-константа '" + condition + "': одна из веток не выполнится никогда; удалите мертвый код"
                        + " либо верните настоящее условие");
    }

    private boolean isJump(Statement statement) {
        return statement.isReturnStmt() || statement.isThrowStmt()
                || statement.isBreakStmt() || statement.isContinueStmt();
    }

    private boolean isConstant(Expression condition) {
        return Nodes.unwrap(condition).isBooleanLiteralExpr();
    }
}
