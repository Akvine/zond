package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
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

/**
 * {@code list.indexOf(x)} в цикле. Поиск позиции - это перебор списка с начала, и в цикле он повторяется
 * для каждого элемента: O(N×M). Позиции достаточно собрать один раз: проход по списку складывает их
 * в {@code Map<элемент, индекс>}, а в цикле индекс берется из нее за O(1).
 */
@Component
public class IndexOfInLoopRule extends AbstractRule {
    private static final Set<String> INDEX_SEARCH = Set.of("indexOf", "lastIndexOf");
    private static final Set<String> LIST_TYPES =
            Set.of("List", "ArrayList", "LinkedList", "CopyOnWriteArrayList", "Vector");

    @Override
    public String code() {
        return RuleCodes.INDEX_OF_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет List.indexOf() в цикле вместо заранее построенной карты индексов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!INDEX_SEARCH.contains(call.getNameAsString()) || call.getArguments().size() != 1
                    || call.getScope().isEmpty() || TestClasses.isInside(call)) {
                continue;
            }
            Expression list = Nodes.unwrap(call.getScope().get());
            // У строки тоже есть indexOf - нужен именно список
            Optional<Node> declaration = LocalTypes.findDeclaration(list)
                    .filter(found -> LocalTypes.declaredType(found).filter(LIST_TYPES::contains).isPresent());
            Optional<Node> loop = declaration.flatMap(found -> CodeContexts.loopOverOuter(call, found));
            if (loop.isEmpty()) {
                continue;
            }
            violations.add(violation(sourceFile, call.getName(), isCurrentElement(call, list, loop.get())
                    ? "'" + Nodes.text(call) + "' ищет в списке позицию элемента, который сам же сейчас"
                    + " перебирается: это перебор с начала на каждый элемент, O(N²), а при одинаковых элементах"
                    + " еще и неверный индекс; ведите счетчик либо используйте цикл с индексом"
                    : "'" + Nodes.text(call) + "' в цикле: поиск позиции - это перебор списка с начала, вместе"
                    + " с циклом выходит O(N×M); один раз пройдите по списку и сложите позиции в Map<элемент,"
                    + " индекс>, а в цикле берите индекс из нее"));
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

    // for (Item item : items) { int position = items.indexOf(item); }
    private boolean isCurrentElement(MethodCallExpr call, Expression list, Node loop) {
        return loop instanceof ForEachStmt each
                && each.getIterable().toString().equals(list.toString())
                && each.getVariable().getVariables().stream()
                .anyMatch(variable -> variable.getNameAsString().equals(call.getArgument(0).toString()));
    }
}
