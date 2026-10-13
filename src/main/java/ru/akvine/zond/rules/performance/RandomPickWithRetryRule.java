package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Выбор неповторяющихся элементов по случайному индексу с отбрасыванием повторов.
 * <p>
 * Первый элемент находится с первой попытки, а дальше каждый следующий - все реже: когда из ста
 * элементов выбрано девяносто, новый выпадает в одной попытке из десяти. Чтобы выбрать все N элементов,
 * нужно в среднем N·ln(N) попыток, а если запросили больше, чем есть в источнике, цикл не закончится.
 * Перемешивание копии списка дает тот же результат за один проход.
 */
@Component
public class RandomPickWithRetryRule extends AbstractRule {
    private static final String GET = "get";
    private static final String ADD = "add";
    private static final Set<String> MEMBERSHIP = Set.of("contains", "containsKey", ADD);
    // random.nextInt(list.size()), (int) (Math.random() * list.size())
    private static final Pattern RANDOM_INDEX = Pattern.compile("\\bnext(Int|Long)\\(|Math\\.random\\(\\)");

    @Override
    public String code() {
        return RuleCodes.RANDOM_PICK_WITH_RETRY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет выбор неповторяющихся элементов по случайному индексу с отбрасыванием"
                + " повторов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        Set<Node> reported = new HashSet<>();
        List<Expression> candidates = new ArrayList<>(sourceFile.unit().findAll(MethodCallExpr.class));
        candidates.addAll(sourceFile.unit().findAll(ArrayAccessExpr.class));
        for (Expression candidate : candidates) {
            if (!TestClasses.isInside(candidate)) {
                check(sourceFile, candidate, reported).ifPresent(violations::add);
            }
        }
        return violations;
    }

    private Optional<Violation> check(SourceFile sourceFile, Expression candidate, Set<Node> reported) {
        return Loops.enclosingIteration(candidate)
                .filter(loop -> !(loop instanceof LambdaExpr) && !reported.contains(loop))
                .flatMap(loop -> pickedNames(candidate)
                        .flatMap(names -> uniquenessCheck(loop, names))
                        .map(check -> {
                            // На один цикл - одна находка
                            reported.add(loop);
                            return violation(sourceFile, candidate, message(candidate, check));
                        }));
    }

    private static String message(Expression pick, MethodCallExpr check) {
        return "'" + Nodes.text(pick) + "' выбирает элемент по случайному индексу, а повторы отбрасываются ('"
                + Nodes.text(check) + "'): чем больше уже выбрано, тем реже выпадает новый элемент и тем больше"
                + " попыток уходит впустую, а если нужно больше элементов, чем есть в источнике, цикл не"
                + " закончится; перемешайте копию списка (Collections.shuffle) и возьмите первые N элементов";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    /**
     * Узнает выбор по случайному индексу: {@code source.get(random.nextInt(source.size()))} либо
     * {@code source.get(index)}, где {@code index} - случайное число.
     *
     * @return имена, под которыми выбранное может проверяться на повтор: переменная с элементом,
     * переменная с индексом и само выражение
     */
    private Optional<Set<String>> pickedNames(Expression candidate) {
        Expression index;
        if (candidate instanceof MethodCallExpr call) {
            if (!GET.equals(call.getNameAsString()) || call.getArguments().size() != 1 || call.getScope().isEmpty()) {
                return Optional.empty();
            }
            index = Nodes.unwrap(call.getArgument(0));
        } else {
            index = Nodes.unwrap(((ArrayAccessExpr) candidate).getIndex());
        }
        Set<String> names = new HashSet<>();
        Expression origin = index;
        if (index.isNameExpr()) {
            // По индексу тоже проверяют повтор: used.contains(index)
            names.add(index.asNameExpr().getNameAsString());
            origin = LocalTypes.findInitializer(index).map(Nodes::unwrap).orElse(index);
        }
        if (!RANDOM_INDEX.matcher(origin.toString()).find()) {
            return Optional.empty();
        }
        target(candidate).ifPresent(names::add);
        // Выбранный элемент может проверяться и без переменной: seen.add(source.get(...))
        names.add(candidate.toString());
        return Optional.of(names);
    }

    // T chosen = source.get(...);  chosen = source.get(...);
    private Optional<String> target(Expression pick) {
        Node parent = pick.getParentNode().orElse(null);
        if (parent instanceof VariableDeclarator variable) {
            return Optional.of(variable.getNameAsString());
        }
        if (parent instanceof AssignExpr assignment && assignment.getValue() == pick) {
            return Optional.of(assignment.getTarget().toString());
        }
        return Optional.empty();
    }

    // result.contains(chosen), !seen.add(chosen), used.contains(index)
    private Optional<MethodCallExpr> uniquenessCheck(Node loop, Set<String> picked) {
        return loop.findAll(MethodCallExpr.class).stream()
                .filter(call -> MEMBERSHIP.contains(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> picked.contains(call.getArgument(0).toString()))
                // add считается проверкой, только когда по его результату что-то решают
                .filter(call -> !ADD.equals(call.getNameAsString()) || isCondition(call))
                .findFirst();
    }

    private boolean isCondition(MethodCallExpr call) {
        Node parent = call.getParentNode().orElse(null);
        return parent != null && !(parent instanceof ExpressionStmt);
    }
}
