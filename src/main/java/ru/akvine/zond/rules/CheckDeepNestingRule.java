package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckDeepNestingRule extends AbstractRule {
    private static final int MAX_DEPTH = 4;

    @Override
    public String code() {
        return RuleCodes.CHECK_DEEP_NESTING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет управляющие конструкции, вложенные глубже " + MAX_DEPTH + " уровней";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Сообщаем о конструкции, с которой предел превышен; то, что вложено еще глубже, не дублируем
        return sourceFile.unit().findAll(Statement.class).stream()
                .filter(this::isNesting)
                .filter(statement -> depth(statement) == MAX_DEPTH + 1)
                .filter(statement -> !TestClasses.isInside(statement))
                .map(statement -> violation(sourceFile, statement,
                        "Вложенность управляющих конструкций больше " + MAX_DEPTH + " уровней: чтобы понять"
                                + " эту строку, нужно удержать в голове все условия выше; вынесите вложенную"
                                + " часть в отдельный метод или выходите из метода раньше"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean isNesting(Node node) {
        // else if продолжает цепочку на том же уровне, а не углубляет ее
        if (node instanceof IfStmt ifStmt) {
            return !isElseIf(ifStmt);
        }
        return node instanceof ForStmt
                || node instanceof ForEachStmt
                || node instanceof WhileStmt
                || node instanceof DoStmt
                || node instanceof SwitchStmt
                || node instanceof TryStmt;
    }

    private boolean isElseIf(IfStmt ifStmt) {
        return ifStmt.getParentNode()
                .filter(parent -> parent instanceof IfStmt outer
                        && outer.getElseStmt().filter(branch -> branch == ifStmt).isPresent())
                .isPresent();
    }

    // Глубина считается в пределах одного метода
    private int depth(Node statement) {
        int depth = 1;
        Node current = statement.getParentNode().orElse(null);
        while (current != null && !(current instanceof BodyDeclaration<?>)) {
            if (isNesting(current)) {
                depth++;
            }
            current = current.getParentNode().orElse(null);
        }
        return depth;
    }
}
