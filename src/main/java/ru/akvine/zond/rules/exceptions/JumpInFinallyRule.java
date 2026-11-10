package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

@Component
public class JumpInFinallyRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.JUMP_IN_FINALLY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет return, throw, break и continue в блоке finally";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        // Переход во вложенном finally виден из обоих блоков, а сообщить о нем нужно один раз
        Set<Node> jumps = Collections.newSetFromMap(new IdentityHashMap<>());
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

            // break и continue, которые уводят из finally в цикл снаружи, глушат исключение так же, как return
            for (BreakStmt breakStmt : finallyBlock.findAll(BreakStmt.class)) {
                String label = breakStmt.getLabel().map(SimpleName::asString).orElse("");
                if (leavesFinally(breakStmt, label, finallyBlock, true) && jumps.add(breakStmt)) {
                    violations.add(violation(sourceFile, breakStmt,
                            "break в блоке finally: исключение из try / catch будет потеряно,"
                                    + " а цикл молча закончится, как будто ошибки не было"));
                }
            }
            for (ContinueStmt continueStmt : finallyBlock.findAll(ContinueStmt.class)) {
                String label = continueStmt.getLabel().map(SimpleName::asString).orElse("");
                if (leavesFinally(continueStmt, label, finallyBlock, false) && jumps.add(continueStmt)) {
                    violations.add(violation(sourceFile, continueStmt,
                            "continue в блоке finally: исключение из try / catch будет потеряно,"
                                    + " а цикл молча перейдет к следующему элементу"));
                }
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
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

    /**
     * @param label       метка, на которую ведет переход; пустая строка - перехода по метке нет
     * @param exitsSwitch break без метки выходит и из switch, continue - только из цикла
     * @return true, если переход ведет к циклу или метке за пределами finally
     */
    private boolean leavesFinally(Node jump, String label, BlockStmt finallyBlock, boolean exitsSwitch) {
        if (Nodes.isInNestedScope(jump, finallyBlock)) {
            return false;
        }
        Node current = jump.getParentNode().orElse(null);
        while (current != null && current != finallyBlock) {
            boolean loop = current instanceof ForStmt || current instanceof ForEachStmt
                    || current instanceof WhileStmt || current instanceof DoStmt;
            if (label.isEmpty() && (loop || exitsSwitch && current instanceof SwitchStmt)) {
                return false;
            }
            if (!label.isEmpty() && current instanceof LabeledStmt labeled && labeled.getLabel().asString().equals(label)) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return true;
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
