package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Достигающие определения внутри метода: какие присваивания переменной могут дойти до места, где ее читают.
 * <p>
 * Метод проходится в порядке выполнения: в ветке if и case - свое состояние, после развилки состояния
 * сливаются; ветка с известным заранее условием (if (false), switch по известному значению) не выполняется;
 * return, throw, break и continue обрывают путь; тело цикла проходится, пока множества не перестанут расти;
 * в catch доходит все, что присвоено в try. Так bar = param; bar = "safe"; use(bar) - это "safe", а
 * if (cond) bar = param; use(bar) - и param, и прежнее значение.
 * <p>
 * Определение - это узел: объявление переменной (VariableDeclarator), присваивание (AssignExpr), параметр
 * метода или лямбды. Отслеживаются только параметры и локальные переменные самого метода; поля, переменные
 * внешнего метода во вложенном классе и переменные шаблона instanceof остаются без ответа
 */
public final class ReachingDefinitions {
    // Столько проходов по телу цикла хватает, чтобы множества перестали расти
    private static final int MAX_LOOP_PASSES = 4;

    private final Map<NameExpr, Set<Node>> reaching = new IdentityHashMap<>();
    private final Set<String> tracked = new HashSet<>();
    private final Deque<List<State>> breaks = new ArrayDeque<>();
    private final Deque<List<State>> continues = new ArrayDeque<>();

    /**
     * Переменная -> определения, которые до этой точки могут дойти
     */
    private static final class State {
        private final Map<String, Set<Node>> definitions = new HashMap<>();

        State copy() {
            State copy = new State();
            definitions.forEach((name, nodes) -> copy.definitions.put(name, identitySet(nodes)));
            return copy;
        }

        void define(String name, Node definition) {
            Set<Node> nodes = identitySet(List.of());
            nodes.add(definition);
            definitions.put(name, nodes);
        }

        void add(String name, Node definition) {
            definitions.computeIfAbsent(name, key -> identitySet(List.of())).add(definition);
        }

        void replaceWith(State other) {
            definitions.clear();
            if (other != null) {
                other.definitions.forEach((name, nodes) -> definitions.put(name, identitySet(nodes)));
            }
        }

        boolean covers(State other) {
            return other.definitions.entrySet().stream().allMatch(entry -> definitions.containsKey(entry.getKey())
                    && definitions.get(entry.getKey()).containsAll(entry.getValue()));
        }
    }

    private ReachingDefinitions() {
    }

    /**
     * @param callable метод, конструктор или блок инициализации
     */
    public static ReachingDefinitions of(Node callable) {
        ReachingDefinitions analysis = new ReachingDefinitions();
        analysis.analyze(callable);
        return analysis;
    }

    /**
     * @return определения, которые доходят до этого чтения переменной; пусто, если переменная не отслеживается
     * (поле, переменная внешнего метода) либо до чтения не дошло ни одного определения
     */
    public Optional<Set<Node>> definitionsOf(NameExpr usage) {
        return Optional.ofNullable(reaching.get(usage)).map(Collections::unmodifiableSet);
    }

    private void analyze(Node callable) {
        collectTracked(callable);
        State state = new State();
        if (callable instanceof CallableDeclaration<?> declaration) {
            for (Parameter parameter : declaration.getParameters()) {
                state.define(parameter.getNameAsString(), parameter);
            }
        }
        callable.getChildNodes().stream()
                .filter(BlockStmt.class::isInstance)
                .map(BlockStmt.class::cast)
                .findFirst()
                .or(() -> callable instanceof InitializerDeclaration initializer ? Optional.of(initializer.getBody())
                        : Optional.empty())
                .ifPresent(body -> execute(body, state));
    }

    // Параметры и локальные переменные самого метода и его лямбд, без вложенных и анонимных классов
    private void collectTracked(Node callable) {
        callable.findAll(VariableDeclarator.class).stream()
                .filter(variable -> isOwn(variable, callable))
                .forEach(variable -> tracked.add(variable.getNameAsString()));
        callable.findAll(Parameter.class).stream()
                .filter(parameter -> isOwn(parameter, callable))
                .forEach(parameter -> tracked.add(parameter.getNameAsString()));
    }

    private boolean isOwn(Node node, Node callable) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != callable) {
            if (current instanceof TypeDeclaration<?> || current instanceof ObjectCreationExpr creation
                    && creation.getAnonymousClassBody().isPresent()
                    && creation.getAnonymousClassBody().get().stream().anyMatch(member -> member.isAncestorOf(node))) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return current == callable;
    }

    // ---------------------------------------------------------------------------------------- операторы

    /**
     * @return состояние после оператора; null, если выполнение дальше не идет (return, throw, break, continue)
     */
    private State execute(Statement statement, State state) {
        if (statement instanceof BlockStmt block) {
            State current = state;
            for (Statement inner : block.getStatements()) {
                if (current == null) {
                    return null;
                }
                current = execute(inner, current);
            }
            return current;
        }
        if (statement instanceof ExpressionStmt expression) {
            visit(expression.getExpression(), state);
            return state;
        }
        if (statement instanceof IfStmt branch) {
            return executeIf(branch, state);
        }
        if (statement instanceof WhileStmt loop) {
            return loop(loop.getCondition(), loop.getBody(), List.of(), null, state);
        }
        if (statement instanceof DoStmt loop) {
            return loop(loop.getCondition(), loop.getBody(), List.of(), null, state);
        }
        if (statement instanceof ForStmt loop) {
            loop.getInitialization().forEach(initialization -> visit(initialization, state));
            return loop(loop.getCompare().orElse(null), loop.getBody(), loop.getUpdate(), null, state);
        }
        if (statement instanceof ForEachStmt loop) {
            visit(loop.getIterable(), state);
            return loop(null, loop.getBody(), List.of(), loop.getVariable(), state);
        }
        if (statement instanceof SwitchStmt choice) {
            return executeSwitch(choice, state);
        }
        if (statement instanceof TryStmt attempt) {
            return executeTry(attempt, state);
        }
        if (statement instanceof ReturnStmt returned) {
            returned.getExpression().ifPresent(value -> visit(value, state));
            return null;
        }
        if (statement instanceof ThrowStmt thrown) {
            visit(thrown.getExpression(), state);
            return null;
        }
        if (statement instanceof BreakStmt) {
            Optional.ofNullable(breaks.peek()).ifPresent(targets -> targets.add(state.copy()));
            return null;
        }
        if (statement instanceof ContinueStmt) {
            Optional.ofNullable(continues.peek()).ifPresent(targets -> targets.add(state.copy()));
            return null;
        }
        if (statement instanceof LabeledStmt labeled) {
            return execute(labeled.getStatement(), state);
        }
        if (statement instanceof SynchronizedStmt synchronizedStatement) {
            visit(synchronizedStatement.getExpression(), state);
            return execute(synchronizedStatement.getBody(), state);
        }
        if (statement instanceof AssertStmt assertion) {
            visit(assertion.getCheck(), state);
            assertion.getMessage().ifPresent(message -> visit(message, state));
            return state;
        }
        if (statement instanceof ExplicitConstructorInvocationStmt invocation) {
            invocation.getArguments().forEach(argument -> visit(argument, state));
            return state;
        }
        if (statement instanceof LocalClassDeclarationStmt || statement instanceof LocalRecordDeclarationStmt) {
            return state;
        }
        // yield и прочие редкие операторы: выражения в них читаются как есть
        statement.getChildNodes().stream()
                .filter(Expression.class::isInstance)
                .forEach(child -> visit((Expression) child, state));
        return state;
    }

    private State executeIf(IfStmt branch, State state) {
        visit(branch.getCondition(), state);
        Optional<Boolean> known = ConstantValues.condition(branch.getCondition());
        if (known.isPresent()) {
            if (known.get()) {
                return execute(branch.getThenStmt(), state);
            }
            return branch.getElseStmt().isPresent() ? execute(branch.getElseStmt().get(), state) : state;
        }
        State thenEnd = execute(branch.getThenStmt(), state.copy());
        State elseEnd = branch.getElseStmt().isPresent() ? execute(branch.getElseStmt().get(), state.copy()) : state;
        return join(thenEnd, elseEnd);
    }

    // Голова цикла копит все, что приходит с прошлых итераций; выход - с головы (условие ложно) и из break
    private State loop(Expression condition, Statement body, List<Expression> update, VariableDeclarationExpr element,
                       State entry) {
        boolean endless = condition != null && ConstantValues.condition(condition).orElse(false);
        State head = entry.copy();
        List<State> exits = new ArrayList<>();
        for (int pass = 0; pass < MAX_LOOP_PASSES; pass++) {
            State start = head.copy();
            if (condition != null) {
                visit(condition, start);
            }
            if (element != null) {
                element.getVariables().forEach(variable -> start.define(variable.getNameAsString(), variable));
            }
            breaks.push(new ArrayList<>());
            continues.push(new ArrayList<>());
            State bodyEnd = execute(body, start.copy());
            exits.addAll(breaks.pop());
            List<State> repeated = new ArrayList<>(continues.pop());
            if (bodyEnd != null) {
                repeated.add(bodyEnd);
            }
            State back = null;
            for (State state : repeated) {
                back = join(back, state);
            }
            if (back != null) {
                for (Expression step : update) {
                    visit(step, back);
                }
            }
            State next = join(head, back);
            if (!endless) {
                exits.add(start);
            }
            if (head.covers(next)) {
                break;
            }
            head = next;
        }
        State exit = null;
        for (State state : exits) {
            exit = join(exit, state);
        }
        return exit;
    }

    private State executeSwitch(SwitchStmt choice, State state) {
        visit(choice.getSelector(), state);
        Optional<List<SwitchEntry>> live = ConstantValues.liveEntries(choice);
        boolean hasDefault = choice.getEntries().stream().anyMatch(entry -> entry.getLabels().isEmpty());
        breaks.push(new ArrayList<>());
        List<State> exits = new ArrayList<>();
        State fallthrough = null;
        for (SwitchEntry entry : choice.getEntries()) {
            if (live.isPresent() && live.get().stream().noneMatch(found -> found == entry)) {
                fallthrough = null;
                continue;
            }
            State start = entry.getType() == SwitchEntry.Type.STATEMENT_GROUP
                    ? join(state.copy(), fallthrough)
                    : state.copy();
            State end = start;
            for (Statement inner : entry.getStatements()) {
                if (end == null) {
                    break;
                }
                end = execute(inner, end);
            }
            if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
                fallthrough = end;
            } else if (end != null) {
                exits.add(end);
            }
        }
        exits.addAll(breaks.pop());
        if (fallthrough != null) {
            exits.add(fallthrough);
        }
        // Значение не совпало ни с одной меткой, а default нет - switch не выполнил ничего
        if (live.map(List::isEmpty).orElse(!hasDefault)) {
            exits.add(state);
        }
        State exit = null;
        for (State end : exits) {
            exit = join(exit, end);
        }
        return exit;
    }

    // В catch доходит все, что успело присвоиться в try до исключения: считаем, что любое из присваиваний
    private State executeTry(TryStmt attempt, State state) {
        attempt.getResources().forEach(resource -> visit(resource, state));
        State tryEnd = execute(attempt.getTryBlock(), state.copy());
        State caught = state.copy();
        attempt.getTryBlock().findAll(AssignExpr.class).stream()
                .filter(assignment -> assignment.getTarget() instanceof NameExpr name && tracked.contains(name.getNameAsString()))
                .forEach(assignment -> caught.add(((NameExpr) assignment.getTarget()).getNameAsString(), assignment));
        attempt.getTryBlock().findAll(VariableDeclarator.class)
                .forEach(variable -> caught.add(variable.getNameAsString(), variable));
        State normal = tryEnd;
        for (CatchClause clause : attempt.getCatchClauses()) {
            State inCatch = caught.copy();
            inCatch.define(clause.getParameter().getNameAsString(), clause.getParameter());
            normal = join(normal, execute(clause.getBody(), inCatch));
        }
        if (attempt.getFinallyBlock().isPresent()) {
            State finallyEnd = execute(attempt.getFinallyBlock().get(), join(normal == null ? null : normal.copy(), caught));
            return normal == null || finallyEnd == null ? null : finallyEnd;
        }
        return normal;
    }

    // --------------------------------------------------------------------------------------- выражения

    private void visit(Expression expression, State state) {
        if (expression == null || state == null) {
            return;
        }
        if (expression instanceof NameExpr name) {
            record(name, state);
            return;
        }
        if (expression instanceof AssignExpr assignment) {
            visitAssignment(assignment, state);
            return;
        }
        if (expression instanceof VariableDeclarationExpr declaration) {
            for (VariableDeclarator variable : declaration.getVariables()) {
                variable.getInitializer().ifPresent(initializer -> visit(initializer, state));
                state.define(variable.getNameAsString(), variable);
            }
            return;
        }
        if (expression instanceof ConditionalExpr branch) {
            visit(branch.getCondition(), state);
            Optional<Boolean> known = ConstantValues.condition(branch.getCondition());
            if (known.isPresent()) {
                visit(known.get() ? branch.getThenExpr() : branch.getElseExpr(), state);
                return;
            }
            State thenState = state.copy();
            visit(branch.getThenExpr(), thenState);
            State elseState = state.copy();
            visit(branch.getElseExpr(), elseState);
            state.replaceWith(join(thenState, elseState));
            return;
        }
        if (expression instanceof BinaryExpr binary
                && (binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR)) {
            visit(binary.getLeft(), state);
            State right = state.copy();
            visit(binary.getRight(), right);
            state.replaceWith(join(state, right));
            return;
        }
        if (expression instanceof LambdaExpr lambda) {
            // Тело выполнится неизвестно когда: видит то, что есть сейчас, а снаружи ничего не меняет
            State inner = state.copy();
            lambda.getParameters().forEach(parameter -> inner.define(parameter.getNameAsString(), parameter));
            breaks.push(new ArrayList<>());
            continues.push(new ArrayList<>());
            execute(lambda.getBody(), inner);
            breaks.pop();
            continues.pop();
            return;
        }
        if (expression instanceof ObjectCreationExpr creation) {
            creation.getScope().ifPresent(scope -> visit(scope, state));
            creation.getArguments().forEach(argument -> visit(argument, state));
            return;
        }
        if (expression instanceof SwitchExpr choice) {
            visit(choice.getSelector(), state);
            State merged = null;
            for (SwitchEntry entry : choice.getEntries()) {
                State branch = state.copy();
                for (Statement inner : entry.getStatements()) {
                    if (branch == null) {
                        break;
                    }
                    branch = execute(inner, branch);
                }
                merged = join(merged, branch);
            }
            state.replaceWith(join(state, merged));
            return;
        }
        for (Node child : expression.getChildNodes()) {
            if (child instanceof Expression inner) {
                visit(inner, state);
            }
        }
    }

    private void visitAssignment(AssignExpr assignment, State state) {
        visit(assignment.getValue(), state);
        if (assignment.getTarget() instanceof NameExpr target && tracked.contains(target.getNameAsString())) {
            if (assignment.getOperator() == AssignExpr.Operator.ASSIGN) {
                state.define(target.getNameAsString(), assignment);
            } else {
                // total += part: новое значение собрано и из прежнего
                record(target, state);
                state.add(target.getNameAsString(), assignment);
            }
            return;
        }
        visit(assignment.getTarget(), state);
    }

    // Чтение переменной: запоминаем, что до него доходит; при нескольких проходах цикла множества объединяются
    private void record(NameExpr usage, State state) {
        String name = usage.getNameAsString();
        Set<Node> definitions = state.definitions.get(name);
        if (!tracked.contains(name) || definitions == null) {
            return;
        }
        reaching.computeIfAbsent(usage, key -> identitySet(List.of())).addAll(definitions);
    }

    private static State join(State left, State right) {
        if (left == null) {
            return right == null ? null : right.copy();
        }
        State joined = left.copy();
        if (right != null) {
            right.definitions.forEach((name, nodes) -> joined.definitions
                    .computeIfAbsent(name, key -> identitySet(List.of())).addAll(nodes));
        }
        return joined;
    }

    private static Set<Node> identitySet(Collection<Node> nodes) {
        Set<Node> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(nodes);
        return set;
    }
}
