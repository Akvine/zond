package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Ветки выполнения: могут ли два места в коде сработать за один вызов метода
 */
@UtilityClass
public class Branches {

    /**
     * Два места исключают друг друга, когда лежат в разных ветках if / else, тернарного оператора или switch
     * либо когда после первого метод всегда завершается:
     * <pre>
     * if (found) { repository.save(existing); return; }
     * otherRepository.save(created);
     * </pre>
     *
     * @return true, если за один вызов метода выполнится не больше одного из двух узлов
     */
    public boolean areExclusive(Node first, Node second) {
        Node firstCallable = Nodes.enclosingCallable(first).orElse(null);
        if (first == second || firstCallable != Nodes.enclosingCallable(second).orElse(null)) {
            return false;
        }
        List<Node> firstPath = pathFromRoot(first);
        List<Node> secondPath = pathFromRoot(second);
        int common = 0;
        while (common < firstPath.size() && common < secondPath.size()
                && firstPath.get(common) == secondPath.get(common)) {
            common++;
        }
        // Один узел лежит внутри другого: выполняются вместе
        if (common == 0 || common == firstPath.size() || common == secondPath.size()) {
            return false;
        }
        Node ancestor = firstPath.get(common - 1);
        Node firstChild = firstPath.get(common);
        Node secondChild = secondPath.get(common);
        if (ancestor instanceof IfStmt branch) {
            Node otherwise = branch.getElseStmt().orElse(null);
            return isPair(branch.getThenStmt(), otherwise, firstChild, secondChild);
        }
        if (ancestor instanceof ConditionalExpr choice) {
            return isPair(choice.getThenExpr(), choice.getElseExpr(), firstChild, secondChild);
        }
        if (firstChild instanceof SwitchEntry firstEntry && secondChild instanceof SwitchEntry secondEntry) {
            SwitchEntry earlier = isBefore(firstEntry, secondEntry) ? firstEntry : secondEntry;
            return !fallsThrough(earlier);
        }
        Node earlier = isBefore(first, second) ? first : second;
        List<Node> earlierPath = earlier == first ? firstPath : secondPath;
        return exitsAfter(earlierPath.subList(common, earlierPath.size()));
    }

    private boolean isPair(Node then, Node otherwise, Node firstChild, Node secondChild) {
        return otherwise != null
                && (firstChild == then && secondChild == otherwise || firstChild == otherwise && secondChild == then);
    }

    // case A: save(); case B: ... - без break выполнение переходит в следующую ветку; у "case A ->" перехода нет
    private boolean fallsThrough(SwitchEntry entry) {
        if (entry.getType() != SwitchEntry.Type.STATEMENT_GROUP) {
            return false;
        }
        Optional<Statement> last = entry.getStatements().getLast();
        return last.isEmpty() || !isJump(unwrap(last.get()));
    }

    /**
     * @param path узлы от общего предка (не включая его) до самого узла
     * @return true, если после узла выполнение обязательно покидает метод: узел стоит в самом return / throw
     * либо в блоке, который ими заканчивается
     */
    private boolean exitsAfter(List<Node> path) {
        for (Node node : path) {
            if (node instanceof ReturnStmt || node instanceof ThrowStmt) {
                return true;
            }
            if (node instanceof BlockStmt block
                    && block.getStatements().getLast().filter(Branches::isExit).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean isExit(Statement statement) {
        return statement instanceof ReturnStmt || statement instanceof ThrowStmt;
    }

    private boolean isJump(Statement statement) {
        return isExit(statement) || statement instanceof BreakStmt || statement instanceof ContinueStmt
                || statement instanceof YieldStmt;
    }

    // case A: { save(); break; }
    private Statement unwrap(Statement statement) {
        Statement current = statement;
        while (current instanceof BlockStmt block && block.getStatements().getLast().isPresent()) {
            current = block.getStatements().getLast().get();
        }
        return current;
    }

    private boolean isBefore(Node left, Node right) {
        return left.getBegin().isPresent() && right.getBegin().isPresent()
                && left.getBegin().get().isBefore(right.getBegin().get());
    }

    private List<Node> pathFromRoot(Node node) {
        List<Node> path = new ArrayList<>();
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            path.add(current);
        }
        Collections.reverse(path);
        return path;
    }
}
