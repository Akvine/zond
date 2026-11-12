package ru.akvine.zond.rules.performance;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Один и тот же запрос к репозиторию с теми же аргументами выполняется в методе дважды, а между вызовами ни
 * аргументы, ни данные не менялись: второй запрос вернет то же самое. Повтор внутри одного выражения
 * показывает jr:502, здесь - повтор в разных операторах.
 */
@Component
public class RepeatedQueryInMethodRule extends AbstractRule {
    private static final Pattern READ = Pattern.compile(
            "(find|get|read|query|search|load|fetch|select|count|exists)\\w*");
    // getNextNumber(), findForUpdate(id), getReferenceById(id): ответ меняется либо запроса нет вовсе
    private static final Pattern CHANGING = Pattern.compile(
            "(?i)next|increment|generate|lock|forUpdate|random|reference");
    // Между запросами что-то записали - второй запрос может вернуть другое
    private static final Pattern WRITE = Pattern.compile(
            "(save|delete|remove|update|insert|persist|merge|flush|refresh|create|add|put|increment|decrement|lock"
                    + "|execute|process|handle|apply|change|modify|mark|register|assign|move|cancel|close|complete"
                    + "|approve|reject|pay|send|publish|sync|import|generate|reserve|release|clear|evict|reset|run"
                    + "|perform|start|stop|commit|rollback|transfer|activate|deactivate|block|unblock|confirm"
                    + "|decline|archive|restore|recalculate|rebuild|init)\\w*");
    private static final Pattern SETTER = Pattern.compile("set[A-Z]\\w*");
    private static final Set<UnaryExpr.Operator> STEPS = Set.of(
            UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT,
            UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT);
    // В транзакции повторный поиск по ключу Hibernate отдаст из контекста без запроса
    private static final Set<String> BY_KEY = Set.of("findById", "getById", "existsById");

    private record Repeat(MethodCallExpr first, MethodCallExpr second) {
    }

    @Override
    public String code() {
        return RuleCodes.REPEATED_QUERY_IN_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет один и тот же запрос к репозиторию, который метод выполняет дважды с одними"
                + " аргументами, ничего не меняя между вызовами";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (CallableDeclaration<?> callable : sourceFile.unit().findAll(CallableDeclaration.class)) {
            if (TestClasses.isInside(callable)) {
                continue;
            }
            for (List<MethodCallExpr> same : queriesByText(callable).values()) {
                findRepeat(callable, same).ifPresent(repeat -> violations.add(violation(sourceFile, repeat.second(),
                        "'" + Nodes.text(repeat.second()) + "' выполняется в методе второй раз с теми же аргументами"
                                + " (первый раз - в строке " + line(repeat.first()) + "), а данные между вызовами не"
                                + " менялись: это лишний запрос к базе; сохраните результат первого вызова"
                                + " в переменную")
                        .withLevel(BY_KEY.contains(repeat.second().getNameAsString())
                                ? ErrorLevel.INFO
                                : errorLevel())));
            }
        }
        violations.sort((left, right) -> Integer.compare(left.line(), right.line()));
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

    // Вызванный между запросами метод сервиса мог изменить данные - по одному методу этого не видно
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    private Map<String, List<MethodCallExpr>> queriesByText(CallableDeclaration<?> callable) {
        Map<String, List<MethodCallExpr>> queries = new LinkedHashMap<>();
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (Repositories.isRepositoryCall(call) && READ.matcher(name).matches() && !CHANGING.matcher(name).find()
                    && call.findAncestor(CallableDeclaration.class).filter(owner -> owner == callable).isPresent()) {
                queries.computeIfAbsent(call.toString(), key -> new ArrayList<>()).add(call);
            }
        }
        return queries;
    }

    private Optional<Repeat> findRepeat(CallableDeclaration<?> callable, List<MethodCallExpr> same) {
        for (int index = 1; index < same.size(); index++) {
            MethodCallExpr first = same.get(index - 1);
            MethodCallExpr second = same.get(index);
            if (runOneAfterAnother(first, second) && !changesBetween(callable, first, second)) {
                return Optional.of(new Repeat(first, second));
            }
        }
        return Optional.empty();
    }

    // Оба запроса выполняются за один проход метода: не в разных ветках, не в разных лямбдах и циклах
    private boolean runOneAfterAnother(MethodCallExpr first, MethodCallExpr second) {
        return first.findAncestor(Statement.class).orElse(null) != second.findAncestor(Statement.class).orElse(null)
                && lambdaOf(first) == lambdaOf(second)
                && Loops.enclosingIteration(first).orElse(null) == Loops.enclosingIteration(second).orElse(null)
                && !isEitherOr(first, second);
    }

    private LambdaExpr lambdaOf(MethodCallExpr call) {
        return call.findAncestor(LambdaExpr.class).orElse(null);
    }

    private boolean isEitherOr(MethodCallExpr first, MethodCallExpr second) {
        Node child = first;
        Node current = first.getParentNode().orElse(null);
        while (current != null && !Nodes.isInside(second, current)) {
            // if (cached) { return repository.findByCode(code); } - после этой ветки до второго запроса не доходят
            if (current instanceof BlockStmt block && endsWithExit(block)) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        if (current == null) {
            return false;
        }
        Node other = current.getChildNodes().stream()
                .filter(node -> node == second || Nodes.isInside(second, node))
                .findFirst()
                .orElse(null);
        return areAlternatives(current, child, other);
    }

    // Две части одного оператора, из которых выполняется только одна
    private boolean areAlternatives(Node common, Node branch, Node other) {
        if (common instanceof IfStmt choice) {
            return branch != choice.getCondition() && other != choice.getCondition();
        }
        if (common instanceof ConditionalExpr choice) {
            return branch != choice.getCondition() && other != choice.getCondition();
        }
        // try { repository.findByCode(code) } catch (...) { repository.findByCode(code) } - повторная попытка
        if (common instanceof TryStmt) {
            return branch instanceof CatchClause || other instanceof CatchClause;
        }
        return branch instanceof SwitchEntry && other instanceof SwitchEntry;
    }

    private boolean endsWithExit(BlockStmt block) {
        return block.getStatements().getLast()
                .filter(last -> last.isReturnStmt() || last.isThrowStmt()
                        || last.isContinueStmt() || last.isBreakStmt())
                .isPresent();
    }

    private boolean changesBetween(CallableDeclaration<?> callable, MethodCallExpr first, MethodCallExpr second) {
        Set<String> names = first.findAll(NameExpr.class).stream()
                .map(NameExpr::getNameAsString)
                .collect(Collectors.toSet());
        Position from = first.getEnd().orElseThrow();
        Position to = second.getBegin().orElseThrow();
        return callable.findAll(Node.class).stream()
                .filter(node -> node.getBegin().filter(begin -> begin.isAfter(from) && begin.isBefore(to)).isPresent())
                .anyMatch(node -> changes(node, names));
    }

    private boolean changes(Node node, Set<String> names) {
        if (node instanceof AssignExpr assignment) {
            return isOneOf(assignment.getTarget(), names);
        }
        if (node instanceof UnaryExpr step) {
            return STEPS.contains(step.getOperator()) && isOneOf(step.getExpression(), names);
        }
        if (!(node instanceof MethodCallExpr call)) {
            return false;
        }
        String name = call.getNameAsString();
        // filter.setStatus(status) между запросами меняет аргумент; сеттеры посторонних объектов запросу не мешают
        if (SETTER.matcher(name).matches()) {
            return call.getScope().filter(scope -> isOneOf(root(scope), names)).isPresent();
        }
        return WRITE.matcher(name).matches();
    }

    private boolean isOneOf(Expression expression, Set<String> names) {
        return expression.isNameExpr() && names.contains(expression.asNameExpr().getNameAsString());
    }

    // order.getClient().setName(...) -> order
    private Expression root(Expression expression) {
        Expression current = Nodes.unwrap(expression);
        while (true) {
            if (current.isMethodCallExpr() && current.asMethodCallExpr().getScope().isPresent()) {
                current = Nodes.unwrap(current.asMethodCallExpr().getScope().get());
            } else if (current.isFieldAccessExpr()) {
                current = Nodes.unwrap(current.asFieldAccessExpr().getScope());
            } else {
                return current;
            }
        }
    }

    private int line(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }
}
