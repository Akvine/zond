package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Удаление из начала списка и вставка в начало - в цикле.
 * <p>
 * {@code ArrayList} хранит элементы подряд, и {@code remove(0)} или {@code add(0, x)} сдвигает все
 * остальные на одну позицию. Разобрать так очередь из N элементов - N сдвигов по N элементов, O(N²).
 * {@code ArrayDeque} берет и добавляет элементы с обоих концов за O(1).
 */
@Component
public class ListShiftInLoopRule extends AbstractRule {
    private static final String REMOVE = "remove";
    private static final String REMOVE_FIRST = "removeFirst";
    private static final String ADD = "add";
    private static final String ADD_FIRST = "addFirst";
    private static final String HEAD = "0";
    private static final Set<String> ARRAY_LISTS = Set.of("List", "ArrayList", "CopyOnWriteArrayList", "Vector");
    // List<Task> queue = new LinkedList<>(): у связного списка начало дешевое
    private static final Pattern LINKED = Pattern.compile("LinkedList|ArrayDeque");

    private enum Shift {REMOVE_HEAD, ADD_HEAD}

    @Override
    public String code() {
        return RuleCodes.LIST_SHIFT_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет удаление из начала списка и вставку в начало (remove(0), add(0, x)) в цикле";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<Shift> shift = shift(call);
            if (shift.isEmpty() || call.getScope().isEmpty() || TestClasses.isInside(call)) {
                continue;
            }
            Expression list = Nodes.unwrap(call.getScope().get());
            // Список объявлен до цикла: с каждой итерацией сдвигается он же
            Optional<Node> loop = LocalTypes.findDeclaration(list)
                    .filter(this::isArrayBacked)
                    .flatMap(declaration -> CodeContexts.loopOverOuter(call, declaration));
            if (loop.isPresent()) {
                violations.add(violation(sourceFile, call.getName(), message(call, list, shift.get())));
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
        return ErrorType.PERFORMANCE;
    }

    private Optional<Shift> shift(MethodCallExpr call) {
        String name = call.getNameAsString();
        int arguments = call.getArguments().size();
        if (REMOVE.equals(name) && arguments == 1 && isHead(call.getArgument(0))
                || REMOVE_FIRST.equals(name) && arguments == 0) {
            return Optional.of(Shift.REMOVE_HEAD);
        }
        if (ADD.equals(name) && arguments == 2 && isHead(call.getArgument(0))
                || ADD_FIRST.equals(name) && arguments == 1) {
            return Optional.of(Shift.ADD_HEAD);
        }
        return Optional.empty();
    }

    private boolean isHead(Expression index) {
        Expression value = Nodes.unwrap(index);
        return value.isIntegerLiteralExpr() && HEAD.equals(value.asIntegerLiteralExpr().getValue());
    }

    private boolean isArrayBacked(Node declaration) {
        boolean list = LocalTypes.declaredType(declaration).filter(ARRAY_LISTS::contains).isPresent();
        boolean linked = declaration instanceof VariableDeclarator variable && variable.getInitializer()
                .filter(initializer -> LINKED.matcher(initializer.toString()).find())
                .isPresent();
        return list && !linked;
    }

    private String message(MethodCallExpr call, Expression list, Shift shift) {
        String start = "'" + Nodes.text(call) + "' в цикле: ";
        if (shift == Shift.REMOVE_HEAD) {
            return start + "удаление из начала списка сдвигает все оставшиеся элементы, и разбор списка целиком"
                    + " стоит O(N²); держите очередь в ArrayDeque (pollFirst берет элемент за O(1)) либо пройдите"
                    + " по '" + list + "' обычным циклом и очистите его после";
        }
        return start + "вставка в начало списка сдвигает все его элементы, и N вставок стоят O(N²); собирайте в"
                + " ArrayDeque (addFirst работает за O(1)) либо добавляйте в конец и один раз разверните список"
                + " (Collections.reverse)";
    }
}
