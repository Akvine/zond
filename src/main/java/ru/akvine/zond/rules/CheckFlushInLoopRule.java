package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckFlushInLoopRule extends AbstractRule {
    private static final Set<String> FLUSH_METHODS = Set.of("flush", "saveAndFlush", "saveAllAndFlush");

    @Override
    public String code() {
        return RuleCodes.CHECK_FLUSH_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет flush() и saveAndFlush() на каждой итерации цикла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> FLUSH_METHODS.contains(call.getNameAsString()) && call.getScope().isPresent())
                .filter(call -> !TestClasses.isInside(call))
                .filter(this::isFlushedOnEveryIteration)
                .map(call -> violation(sourceFile, call,
                        "'" + call.getScope().get() + "." + call.getNameAsString() + "(...)' на каждой итерации:"
                                + " каждый элемент отправляется в БД отдельным обращением, пакетная запись"
                                + " не работает; сбрасывайте контекст раз в несколько десятков элементов"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // if (i % 50 == 0) em.flush() - это и есть сброс пачками: условие внутри цикла его разрешает
    private boolean isFlushedOnEveryIteration(MethodCallExpr call) {
        Optional<Node> iteration = Loops.enclosingIteration(call);
        if (iteration.isEmpty()) {
            return false;
        }
        Node current = call.getParentNode().orElse(null);
        while (current != null && current != iteration.get()) {
            if (current instanceof IfStmt) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return true;
    }
}
