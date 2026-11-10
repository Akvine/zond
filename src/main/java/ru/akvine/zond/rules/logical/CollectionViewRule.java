package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Представление коллекции, принятое за копию. Collections.unmodifiableList(list) и list.subList(a, b) данных
 * не копируют: это окна в исходную коллекцию. Изменения исходной коллекции видны сквозь unmodifiable-обертку,
 * а после изменения размера исходного списка любое обращение к его subList бросает
 * ConcurrentModificationException.
 */
@Component
public class CollectionViewRule extends AbstractRule {
    private static final String COLLECTIONS = "Collections";
    private static final Set<String> UNMODIFIABLE_VIEWS = Set.of(
            "unmodifiableList", "unmodifiableSet", "unmodifiableMap", "unmodifiableCollection", "unmodifiableSortedSet",
            "unmodifiableSortedMap");
    private static final String SUB_LIST = "subList";
    // Меняют размер списка: после них окно subList недействительно
    private static final Set<String> STRUCTURAL_CHANGES = Set.of(
            "add", "addAll", "addFirst", "addLast", "remove", "removeAll", "removeIf", "retainAll", "clear",
            "removeFirst", "removeLast");

    @Override
    public String code() {
        return RuleCodes.COLLECTION_VIEW_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет представления коллекций, принятые за копии: Collections.unmodifiableList от"
                + " чужой коллекции в поле и subList, который переживает изменение исходного списка";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        checkStoredViews(sourceFile, violations);
        checkReturnedViews(sourceFile, violations);
        checkChangesUnderView(sourceFile, violations);
        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    // Представление сохраняют в поле, как будто это копия
    private void checkStoredViews(SourceFile sourceFile, List<Violation> violations) {
        for (AssignExpr assignment : sourceFile.unit().findAll(AssignExpr.class)) {
            Expression value = Nodes.unwrap(assignment.getValue());
            if (!value.isMethodCallExpr() || !isField(assignment.getTarget())) {
                continue;
            }
            MethodCallExpr call = value.asMethodCallExpr();
            // this.items = Collections.unmodifiableList(items): вызывающий код держит ссылку на исходный список
            if (isUnmodifiableView(call) && isParameter(call.getArgument(0))) {
                violations.add(violation(sourceFile, call,
                        "'" + Nodes.text(call) + "' сохраняется в поле: это представление, а не копия. Вызывающий код"
                                + " по-прежнему держит исходную коллекцию и может ее менять - изменения будут видны"
                                + " и здесь, так что неизменяемым объект не стал; сохраняйте копию: List.copyOf(...),"
                                + " Set.copyOf(...), Map.copyOf(...)").withLevel(ErrorLevel.MINOR));
            }
            if (isSubList(call)) {
                violations.add(violation(sourceFile, call,
                        "'" + Nodes.text(call) + "' сохраняется в поле: subList - окно в исходный список, а не копия."
                                + " Оно держит в памяти весь исходный список, а после изменения его размера любое"
                                + " обращение к окну бросает ConcurrentModificationException; сохраняйте копию:"
                                + " List.copyOf(list.subList(...)) либо new ArrayList<>(list.subList(...))")
                        .withLevel(ErrorLevel.MINOR));
            }
        }
    }

    // return items.subList(0, 10), где items - поле: наружу уходит окно во внутренний список
    private void checkReturnedViews(SourceFile sourceFile, List<Violation> violations) {
        for (ReturnStmt statement : sourceFile.unit().findAll(ReturnStmt.class)) {
            Optional<MethodCallExpr> view = statement.getExpression().map(Nodes::unwrap)
                    .filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr)
                    .filter(this::isSubList)
                    .filter(call -> call.getScope().filter(this::isField).isPresent());
            view.ifPresent(call -> violations.add(violation(sourceFile, call,
                    "Метод возвращает '" + Nodes.text(call) + "' - окно во внутренний список, а не копию: когда"
                            + " размер списка изменится, вызывающий код получит ConcurrentModificationException на"
                            + " первом же обращении к результату, а через само окно сможет менять внутренний список;"
                            + " возвращайте копию: List.copyOf(...)").withLevel(ErrorLevel.MINOR)));
        }
    }

    // List<Order> head = orders.subList(0, 10); orders.add(order); head.size() -> исключение
    private void checkChangesUnderView(SourceFile sourceFile, List<Violation> violations) {
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            Optional<MethodCallExpr> view = variable.getInitializer().map(Nodes::unwrap)
                    .filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr)
                    .filter(this::isSubList);
            if (view.isEmpty() || variable.getParentNode().filter(parent -> parent instanceof FieldDeclaration).isPresent()) {
                continue;
            }
            String list = view.get().getScope().map(Nodes::text).orElse("");
            changeBeforeUse(variable, list).ifPresent(change -> violations.add(violation(sourceFile, change,
                    "'" + Nodes.text(change) + "' меняет размер списка '" + list + "', пока жив его subList '"
                            + variable.getNameAsString() + "' (строка "
                            + variable.getBegin().map(position -> position.line).orElse(0) + "): subList - окно в"
                            + " исходный список, и после такого изменения любое обращение к '"
                            + variable.getNameAsString() + "' бросит ConcurrentModificationException; возьмите копию"
                            + " (new ArrayList<>(" + list + ".subList(...))) либо меняйте список через само окно")));
        }
    }

    // Строже всех находок - изменение списка при живом subList: это исключение при выполнении
    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    /**
     * @return изменение размера исходного списка, после которого окном еще пользуются
     */
    private Optional<MethodCallExpr> changeBeforeUse(VariableDeclarator view, String list) {
        Optional<Node> callable = Nodes.enclosingCallable(view);
        if (callable.isEmpty()) {
            return Optional.empty();
        }
        String name = view.getNameAsString();
        // Последнее обращение к окну: опасно только изменение списка, которое случилось раньше него
        Optional<NameExpr> lastUse = callable.get().findAll(NameExpr.class).stream()
                .filter(use -> use.getNameAsString().equals(name))
                .reduce((first, second) -> second);
        return callable.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> STRUCTURAL_CHANGES.contains(call.getNameAsString()))
                .filter(call -> call.getScope().filter(scope -> Nodes.text(Nodes.unwrap(scope)).equals(list)).isPresent())
                .filter(call -> isAfter(call, view))
                .filter(call -> lastUse.filter(use -> isAfter(use, call)).isPresent())
                .findFirst();
    }

    private boolean isAfter(Node later, Node earlier) {
        return later.getBegin().isPresent() && earlier.getEnd().isPresent()
                && later.getBegin().get().isAfter(earlier.getEnd().get());
    }

    // Collections.unmodifiableList(x)
    private boolean isUnmodifiableView(MethodCallExpr call) {
        return UNMODIFIABLE_VIEWS.contains(call.getNameAsString()) && call.getArguments().size() == 1
                && call.getScope().filter(scope -> COLLECTIONS.equals(scope.toString())).isPresent();
    }

    private boolean isSubList(MethodCallExpr call) {
        return SUB_LIST.equals(call.getNameAsString()) && call.getArguments().size() == 2 && call.getScope().isPresent();
    }

    private boolean isParameter(Expression expression) {
        return LocalTypes.findDeclaration(Nodes.unwrap(expression)).filter(found -> found instanceof Parameter).isPresent();
    }

    // this.items либо items, объявленное полем
    private boolean isField(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isFieldAccessExpr()) {
            return value.asFieldAccessExpr().getScope().isThisExpr();
        }
        return value.isNameExpr() && LocalTypes.findDeclaration(value)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof FieldDeclaration)
                .isPresent();
    }
}
