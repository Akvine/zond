package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
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
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Сортировка в цикле. Два случая:
 * <ul>
 *     <li>список сортируют заново после каждой вставки - N сортировок вместо одной. Если порядок нужен
 *     только в конце, сортируют один раз после цикла; если на каждом шаге - элементы держат в дереве
 *     (TreeSet, TreeMap, PriorityQueue): вставка в него стоит O(log N), а порядок поддерживается сам;</li>
 *     <li>одну и ту же коллекцию, которая в цикле не меняется, сортируют на каждой итерации.</li>
 * </ul>
 * Разовая сортировка находкой не является: Collections.sort на готовом списке быстрее сборки дерева.
 */
@Component
public class SortInLoopRule extends AbstractRule {
    private static final String SORT = "sort";
    private static final Set<String> SORT_UTILITIES = Set.of("Collections", "Arrays");
    private static final Set<String> LIST_TYPES =
            Set.of("List", "ArrayList", "LinkedList", "CopyOnWriteArrayList", "Vector");
    private static final Set<String> ADDERS = Set.of("add", "addAll", "addFirst", "addLast", "offer", "push");

    @Override
    public String code() {
        return RuleCodes.SORT_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сортировку в цикле: список, который сортируют заново после каждой вставки,"
                + " и повторную сортировку коллекции, которая в цикле не меняется";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<Expression> sorted = sortedCollection(call);
            if (sorted.isEmpty() || TestClasses.isInside(call)) {
                continue;
            }
            // Коллекция объявлена до цикла: на каждой итерации сортируется она же, а не новая
            Optional<Node> loop = LocalTypes.findDeclaration(sorted.get())
                    .flatMap(declaration -> CodeContexts.loopOverOuter(call, declaration));
            if (loop.isEmpty()) {
                continue;
            }
            String name = sorted.get().toString();
            violations.add(violation(sourceFile, call.getName(), isFilledIn(loop.get(), name)
                    ? "'" + Nodes.text(call) + "' сортирует '" + name + "' заново после каждой вставки: N вставок -"
                    + " N сортировок, в сумме O(N² log N); если порядок нужен только в конце, сортируйте один раз"
                    + " после цикла, а если на каждом шаге - держите элементы в TreeSet или TreeMap"
                    + " (PriorityQueue, когда нужен только наименьший): вставка стоит O(log N), порядок"
                    + " поддерживается сам"
                    : "'" + Nodes.text(call) + "' сортирует '" + name + "' на каждой итерации, хотя в цикле"
                    + " коллекция не меняется: вынесите сортировку перед циклом"));
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

    // Collections.sort(list), Arrays.sort(array), list.sort(comparator)
    private Optional<Expression> sortedCollection(MethodCallExpr call) {
        if (!SORT.equals(call.getNameAsString()) || call.getScope().isEmpty() || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = Nodes.unwrap(call.getScope().get());
        if (SORT_UTILITIES.contains(MethodCalls.receiverName(scope)) && scope.isNameExpr()) {
            return Optional.of(Nodes.unwrap(call.getArgument(0)));
        }
        boolean list = LocalTypes.typeOf(scope).filter(LIST_TYPES::contains).isPresent();
        return list && call.getArguments().size() == 1 ? Optional.of(scope) : Optional.empty();
    }

    private boolean isFilledIn(Node loop, String collection) {
        return loop.findAll(MethodCallExpr.class).stream()
                .filter(call -> ADDERS.contains(call.getNameAsString()))
                .anyMatch(call -> call.getScope().map(Expression::toString).filter(collection::equals).isPresent());
    }
}
