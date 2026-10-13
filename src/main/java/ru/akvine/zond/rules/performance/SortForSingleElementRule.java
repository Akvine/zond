package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Список сортируют, чтобы взять из него один элемент - первый или последний.
 * <p>
 * Сортировка стоит O(N log N) и переставляет все элементы, а наименьший или наибольший находится за
 * один проход: {@code Collections.min}, {@code Collections.max}. О том же в стримах
 * ({@code sorted().findFirst()}) сообщает jr:74.
 */
@Component
public class SortForSingleElementRule extends AbstractRule {
    private static final String SORT = "sort";
    private static final Set<String> SORT_UTILITIES = Set.of("Collections", "Arrays");
    private static final Set<String> LIST_TYPES =
            Set.of("List", "ArrayList", "LinkedList", "CopyOnWriteArrayList", "Vector");

    private static final String GET = "get";
    private static final String FIRST = "0";
    private static final Set<String> FIRST_ACCESS = Set.of("getFirst");
    private static final Set<String> LAST_ACCESS = Set.of("getLast");
    // Вызовы, которым порядок элементов не важен
    private static final Set<String> ORDER_AGNOSTIC = Set.of("size", "isEmpty");
    private static final String LENGTH = "length";

    private enum End {FIRST, LAST, NONE}

    @Override
    public String code() {
        return RuleCodes.SORT_FOR_SINGLE_ELEMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сортировку списка или массива ради одного элемента - первого или последнего";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr sort : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<NameExpr> sorted = sortedLocal(sort);
            if (sorted.isEmpty() || TestClasses.isInside(sort)) {
                continue;
            }
            String name = sorted.get().getNameAsString();
            List<NameExpr> later = usagesAfter(sort, name);
            List<End> ends = later.stream().map(usage -> end(usage, name)).toList();
            // После сортировки из коллекции берут только концы: порядок остальных никому не нужен
            boolean onlyEnds = !ends.isEmpty() && later.stream().allMatch(usage -> isOrderAgnostic(usage, name));
            if (onlyEnds && ends.stream().anyMatch(end -> end != End.NONE)) {
                violations.add(violation(sourceFile, sort.getName(), message(sort, name, ends)));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    /**
     * Сортировка отдельным оператором локальной коллекции: {@code Collections.sort(values)},
     * {@code Arrays.sort(numbers)}, {@code values.sort(comparator)}. Параметр и поле не считаются:
     * отсортированными их может ждать вызывающий код.
     */
    private Optional<NameExpr> sortedLocal(MethodCallExpr sort) {
        if (!SORT.equals(sort.getNameAsString()) || sort.getScope().isEmpty() || sort.getArguments().isEmpty()
                || sort.getParentNode().filter(parent -> parent instanceof ExpressionStmt).isEmpty()) {
            return Optional.empty();
        }
        Expression scope = Nodes.unwrap(sort.getScope().get());
        Expression sorted = scope.isNameExpr() && SORT_UTILITIES.contains(scope.toString())
                ? Nodes.unwrap(sort.getArgument(0))
                : scope;
        if (!sorted.isNameExpr()) {
            return Optional.empty();
        }
        boolean utility = sorted != scope;
        boolean local = LocalTypes.findDeclaration(sorted)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .filter(declaration -> declaration.getParentNode()
                        .filter(parent -> parent instanceof FieldDeclaration)
                        .isEmpty())
                .filter(declaration -> utility
                        || LocalTypes.declaredType(declaration).filter(LIST_TYPES::contains).isPresent())
                .isPresent();
        return local ? Optional.of(sorted.asNameExpr()) : Optional.empty();
    }

    private List<NameExpr> usagesAfter(MethodCallExpr sort, String name) {
        return Nodes.enclosingCallable(sort)
                .map(callable -> callable.findAll(NameExpr.class).stream()
                        .filter(usage -> usage.getNameAsString().equals(name))
                        .filter(usage -> usage.getBegin().isPresent() && sort.getEnd().isPresent()
                                && usage.getBegin().get().isAfter(sort.getEnd().get()))
                        .toList())
                .orElse(List.of());
    }

    private boolean isOrderAgnostic(NameExpr usage, String name) {
        if (end(usage, name) != End.NONE) {
            return true;
        }
        Node parent = usage.getParentNode().orElse(null);
        // values.size(), values.isEmpty(), numbers.length
        return parent instanceof MethodCallExpr call && ORDER_AGNOSTIC.contains(call.getNameAsString())
                || parent instanceof FieldAccessExpr access && LENGTH.equals(access.getNameAsString());
    }

    // values.get(0), values.getFirst(), numbers[0] - начало; values.get(values.size() - 1), numbers[numbers.length - 1] - конец
    private End end(NameExpr usage, String name) {
        Node parent = usage.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == usage).isPresent()) {
            if (FIRST_ACCESS.contains(call.getNameAsString())) {
                return End.FIRST;
            }
            if (LAST_ACCESS.contains(call.getNameAsString())) {
                return End.LAST;
            }
            return GET.equals(call.getNameAsString()) && call.getArguments().size() == 1
                    ? indexEnd(call.getArgument(0), name)
                    : End.NONE;
        }
        return parent instanceof ArrayAccessExpr access && access.getName() == usage
                ? indexEnd(access.getIndex(), name)
                : End.NONE;
    }

    private End indexEnd(Expression index, String name) {
        String text = Nodes.unwrap(index).toString().replace(" ", "");
        if (FIRST.equals(text)) {
            return End.FIRST;
        }
        return text.equals(name + ".size()-1") || text.equals(name + ".length-1") ? End.LAST : End.NONE;
    }

    private String message(MethodCallExpr sort, String name, List<End> ends) {
        boolean first = ends.contains(End.FIRST);
        boolean last = ends.contains(End.LAST);
        String replacement = first && last
                ? "Collections.min(" + name + ") и Collections.max(" + name + ")"
                : first ? "Collections.min(" + name + ")" : "Collections.max(" + name + ")";
        return "'" + Nodes.text(sort) + "' сортирует '" + name + "' целиком ради "
                + (first && last ? "первого и последнего элементов" : first ? "первого элемента" : "последнего элемента")
                + ": это O(N log N) и перестановка всех элементов там, где хватает одного прохода; возьмите"
                + " значение через " + replacement + " (с тем же компаратором, если он есть), для массива -"
                + " обычным циклом или Arrays.stream(...).min()";
    }
}
