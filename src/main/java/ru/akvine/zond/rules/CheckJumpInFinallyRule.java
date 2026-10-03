package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckJumpInFinallyRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_JUMP_IN_FINALLY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет return и throw в блоке finally";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (TryStmt tryStmt : sourceFile.unit().findAll(TryStmt.class)) {
            if (tryStmt.getFinallyBlock().isEmpty()) {
                continue;
            }
            BlockStmt finallyBlock = tryStmt.getFinallyBlock().get();

            for (ReturnStmt returnStmt : finallyBlock.findAll(ReturnStmt.class)) {
                if (!Nodes.isInNestedScope(returnStmt, finallyBlock)) {
                    violations.add(violation(sourceFile, returnStmt,
                            "return в блоке finally: исключение из try / catch будет потеряно,"
                                    + " а возвращаемое значение подменено"));
                }
            }

            for (ThrowStmt throwStmt : finallyBlock.findAll(ThrowStmt.class)) {
                if (!Nodes.isInNestedScope(throwStmt, finallyBlock) && !isCaughtInside(throwStmt, finallyBlock)) {
                    violations.add(violation(sourceFile, throwStmt,
                            "throw в блоке finally: исходное исключение из try / catch будет потеряно"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }

    // throw внутри вложенного try с catch наружу из finally может и не выйти
    private boolean isCaughtInside(ThrowStmt throwStmt, BlockStmt finallyBlock) {
        Node child = throwStmt;
        Node current = throwStmt.getParentNode().orElse(null);
        while (current != null && current != finallyBlock) {
            if (current instanceof TryStmt nested
                    && nested.getTryBlock() == child
                    && !nested.getCatchClauses().isEmpty()) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
