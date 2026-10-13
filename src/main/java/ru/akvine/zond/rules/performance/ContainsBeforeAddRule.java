package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CheckThenAdd;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Проверка на повтор через contains перед add.
 * <p>
 * Для списка это перебор всех уже добавленных элементов на каждое добавление: N элементов - N²/2
 * сравнений. Сто тысяч уникальных значений собираются так десятки секунд, а через множество - сотые
 * доли. Для множества поиск быстрый, но делается дважды: add и сам сообщает, был ли элемент.
 */
@Component
public class ContainsBeforeAddRule extends AbstractRule {
    private static final Set<String> LIST_TYPES =
            Set.of("List", "ArrayList", "LinkedList", "CopyOnWriteArrayList", "Vector");
    private static final Set<String> SET_TYPES =
            Set.of("Set", "HashSet", "LinkedHashSet", "TreeSet", "SortedSet", "NavigableSet", "KeySetView");
    private static final String SORT = "sort";

    @Override
    public String code() {
        return RuleCodes.CONTAINS_BEFORE_ADD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет проверку на повтор через contains() перед add(): перебор списка"
                + " вместо множества и двойной поиск в множестве";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr contains : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<MethodCallExpr> add = CheckThenAdd.addAfter(contains).filter(found -> !TestClasses.isInside(found));
            Optional<Node> declaration = add.flatMap(found -> LocalTypes.findDeclaration(contains.getScope().orElseThrow()));
            Optional<String> type = declaration.flatMap(LocalTypes::declaredType);
            if (type.filter(SET_TYPES::contains).isPresent()) {
                add.flatMap(found -> setViolation(sourceFile, contains, found)).ifPresent(violations::add);
            } else if (type.filter(LIST_TYPES::contains).isPresent()) {
                boolean field = declaration.flatMap(Node::getParentNode)
                        .filter(parent -> parent instanceof FieldDeclaration)
                        .isPresent();
                add.flatMap(found -> listViolation(sourceFile, contains, found, field)).ifPresent(violations::add);
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

    private Optional<Violation> listViolation(SourceFile sourceFile, MethodCallExpr contains, MethodCallExpr add,
                                             boolean field) {
        boolean repeated = CodeContexts.isRepeatedOverData(contains);
        // Один раз проверить короткий локальный список - не беда; список в поле растет от вызова к вызову
        if (!repeated && !field) {
            return Optional.empty();
        }
        String list = Nodes.text(contains.getScope().orElseThrow());
        String message = "'" + Nodes.text(contains) + "' ищет повтор перебором списка перед '" + Nodes.text(add)
                + "': поиск линейный, и с каждым добавленным элементом он дольше - в сумме O(N²); заведите рядом"
                + " HashSet и проверяйте само добавление: if (!seen.add(x)) - элемент уже был"
                + (isSorted(contains, list)
                ? "; а раз список потом сортируется, подойдет TreeSet: он и не допускает повторов, и хранит"
                + " элементы по порядку"
                : "");
        return Optional.of(violation(sourceFile, contains.getName(), message)
                .withConfidence(repeated ? Confidence.PROBABLE : Confidence.SUSPICION));
    }

    private Optional<Violation> setViolation(SourceFile sourceFile, MethodCallExpr contains, MethodCallExpr add) {
        if (!isReducible(contains, add)) {
            return Optional.empty();
        }
        return Optional.of(violation(sourceFile, contains.getName(),
                "'" + Nodes.text(contains) + "' и следом '" + Nodes.text(add) + "' ищут элемент дважды: add сам"
                        + " возвращает false, если элемент уже есть; замените пару одним вызовом -"
                        + " if (set.add(x)) { ... } для нового элемента, if (!set.add(x)) { ... } для повтора")
                .withLevel(ErrorLevel.MINOR));
    }

    /**
     * Пару можно заменить одним add, только когда между проверкой и добавлением ничего не стоит:
     * <pre>
     * if (!set.contains(x)) { set.add(x); ... }
     * if (set.contains(x)) { return; } set.add(x);
     * </pre>
     * Проверка должна быть всем условием: в {@code if (!set.contains(x) && isValid(x))} добавление
     * зависит еще и от второй половины, и add на месте проверки добавил бы лишнее.
     */
    private boolean isReducible(MethodCallExpr contains, MethodCallExpr add) {
        Optional<IfStmt> guard = contains.findAncestor(IfStmt.class);
        Optional<Node> addition = add.getParentNode().filter(parent -> parent instanceof ExpressionStmt);
        if (guard.isEmpty() || addition.isEmpty()) {
            return false;
        }
        Expression condition = Nodes.unwrap(guard.get().getCondition());
        boolean negated = condition instanceof UnaryExpr negation
                && negation.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
                && Nodes.unwrap(negation.getExpression()) == contains;
        if (negated) {
            return firstStatement(guard.get().getThenStmt()).filter(first -> first == addition.get()).isPresent();
        }
        return condition == contains && exits(guard.get().getThenStmt())
                && nextStatement(guard.get()).filter(next -> next == addition.get()).isPresent();
    }

    private Optional<Statement> firstStatement(Statement branch) {
        if (branch instanceof BlockStmt block) {
            return block.getStatements().stream().findFirst();
        }
        return Optional.of(branch);
    }

    private Optional<Statement> nextStatement(IfStmt statement) {
        return statement.getParentNode()
                .filter(parent -> parent instanceof BlockStmt)
                .map(parent -> ((BlockStmt) parent).getStatements())
                .flatMap(statements -> {
                    int next = statements.indexOf(statement) + 1;
                    return next < statements.size() ? Optional.of(statements.get(next)) : Optional.empty();
                });
    }

    private boolean exits(Statement statement) {
        Statement last = statement;
        if (statement instanceof BlockStmt block) {
            if (block.getStatements().isEmpty()) {
                return false;
            }
            last = block.getStatement(block.getStatements().size() - 1);
        }
        return last instanceof ContinueStmt || last instanceof ReturnStmt || last instanceof BreakStmt
                || last instanceof ThrowStmt;
    }

    // Collections.sort(list), list.sort(...)
    private boolean isSorted(MethodCallExpr contains, String list) {
        return Nodes.enclosingCallable(contains)
                .map(callable -> callable.findAll(MethodCallExpr.class).stream()
                        .filter(call -> SORT.equals(call.getNameAsString()))
                        .anyMatch(call -> call.getScope().map(Expression::toString).filter(list::equals).isPresent()
                                || call.getArguments().stream().anyMatch(argument -> argument.toString().equals(list))))
                .orElse(false);
    }
}
