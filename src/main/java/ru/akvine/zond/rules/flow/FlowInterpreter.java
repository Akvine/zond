package ru.akvine.zond.rules.flow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
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
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Исполняет тело метода не на настоящих значениях, а на том, что о них известно: null или не null, в каком
 * диапазоне число. На развилке (if, switch, тернарный оператор) каждая ветка исполняется со своим уточненным
 * состоянием, после нее состояния сливаются. Для Java, где нет goto, такой обход по структуре кода равнозначен
 * обходу графа потока управления.
 * <p>
 * Цикл проходится несколько раз, пока состояние в начале итерации не перестанет меняться: с ним и проверяются
 * тело цикла и код после него. Вместе с локальными переменными отслеживаются поля своего объекта.
 */
public final class FlowInterpreter {
    private static final Set<String> NULLABLE_ANNOTATIONS = Set.of("Nullable", "CheckForNull");
    private static final Set<String> INTEGRAL_TYPES = Set.of("int", "long", "short", "byte", "char");
    private static final String BOOLEAN_TYPE = "boolean";
    private static final String LENGTH = "length";

    // Objects.requireNonNull(x), Assert.notNull(x, "..."), Preconditions.checkNotNull(x): после вызова x не null
    private static final Set<String> NULL_ASSERTIONS = Set.of(
            "requireNonNull", "notNull", "checkNotNull", "hasText", "hasLength", "notEmpty", "notBlank",
            "requireNonNullElse", "isNotNull");

    // Objects.isNull(x), StringUtils.isBlank(x), CollectionUtils.isEmpty(x): false означает, что x не null
    private static final Set<String> TRUE_FOR_NULL = Set.of("isNull", "isEmpty", "isBlank", "isNullOrEmpty");

    // Objects.nonNull(x), StringUtils.hasText(x): true означает, что x не null
    private static final Set<String> FALSE_FOR_NULL =
            Set.of("nonNull", "isNotEmpty", "isNotBlank", "hasText", "hasLength", "isNotNull");
    private static final String IS_NULL = "isNull";
    private static final String NON_NULL = "nonNull";
    private static final Set<String> EQUALS_METHODS = Set.of("equals", "equalsIgnoreCase", "contentEquals");
    private static final Set<String> INDEX_SEARCH = Set.of("indexOf", "lastIndexOf");
    private static final Set<String> SIZE_METHODS = Set.of("size", "length");
    private static final Set<String> INDEX_CONSUMERS = Set.of("charAt", "substring");
    private static final Set<String> NEVER_RETURNING = Set.of("exit", "fail");
    private static final List<String> SEARCH_GUARDS = List.of(".contains(", ".startsWith(", ".endsWith(", ".matches(");
    private static final String OR_ELSE = "orElse";
    private static final String INDEX_SEARCH_REASON = "результат поиска";
    private static final Set<String> NON_NULL_ANNOTATIONS = Set.of("NonNull", "Nonnull");
    // Под таким именем в состоянии хранится поле своего объекта
    private static final String THIS = "this.";
    private static final String CHECKED_FOR_NULL = "значение проверено на null";
    // Столько проходов по телу цикла хватает, чтобы состояние в его начале перестало меняться
    private static final int MAX_LOOP_PASSES = 6;

    /**
     * Откуда интерпретатор узнает о вызываемых методах
     */
    public interface Host {
        Optional<FlowSummary> summaryOf(MethodCallExpr call);
    }

    /**
     * Состояния после условия: когда оно истинно и когда ложно. null - такой исход невозможен
     */
    private record Branches(FlowState whenTrue, FlowState whenFalse) {

        Branches swapped() {
            return new Branches(whenFalse, whenTrue);
        }
    }

    /**
     * Выход из цикла или switch по break: состояние в этот момент и метка, если она есть
     */
    private record Break(String label, FlowState state) {
    }

    private final Host host;
    private final boolean reporting;
    private final List<FlowAnalysis.Finding> findings = new ArrayList<>();

    private final List<Break> breaks = new ArrayList<>();
    private final List<Break> continues = new ArrayList<>();
    // Поля своего класса: что известно о каждом на входе в метод. К этому же значению поле возвращается
    // после вызова, который мог его изменить
    private final Map<String, FlowValue> fields = new HashMap<>();
    private boolean mutatesFields;
    // Пробные проходы по телу цикла: находки в них не записываются
    private int silent;
    private final Set<Integer> dereferencedParams = new HashSet<>();
    private final List<String> parameterNames = new ArrayList<>();
    // Переменные, объявленные с примитивным типом: Integer, в отличие от int, может стать null
    private final Set<String> primitives = new HashSet<>();
    private FlowValue returned;
    private FlowState exitState;

    // Глубина вложенности в ветки: обращение к параметру в ветке выполняется не при каждом вызове метода
    private int conditionDepth;
    private int lambdaDepth;
    // Внутри assert условие - это утверждение, а не проверка: сообщать, что оно всегда истинно, не нужно
    private int quietConditions;
    // Только что вызван метод, который не возвращает управление
    private boolean terminated;
    private boolean unreachableReported;
    // Исходы условия, вычисленного последним: нужны, когда его результат кладут в булеву переменную
    private Branches lastCondition;
    private String deathReason = "";

    public FlowInterpreter(Host host, boolean reporting) {
        this.host = host;
        this.reporting = reporting;
    }

    public List<FlowAnalysis.Finding> findings() {
        return findings;
    }

    /**
     * @param overrides значения параметров, заданные заранее: так проверяется, что вернет метод, получив null
     */
    public FlowSummary run(CallableDeclaration<?> callable, Map<Integer, FlowValue> overrides) {
        Optional<BlockStmt> body = callable instanceof MethodDeclaration method
                ? method.getBody()
                : Optional.of(((ConstructorDeclaration) callable).getBody());
        if (body.isEmpty()) {
            return FlowSummary.unknown();
        }

        FlowState state = new FlowState();
        NodeList<Parameter> parameters = callable.getParameters();
        for (int index = 0; index < parameters.size(); index++) {
            Parameter parameter = parameters.get(index);
            parameterNames.add(parameter.getNameAsString());
            rememberType(parameter.getNameAsString(), parameter.getType());
            state.assign(parameter.getNameAsString(),
                    overrides.containsKey(index) ? overrides.get(index) : parameterValue(parameter, index));
        }

        initFields(callable, state);

        FlowState end = execute(body.get(), state);
        exitState = FlowState.join(exitState, end);

        Set<Integer> nonNullAfterCall = new HashSet<>();
        for (int index = 0; exitState != null && index < parameterNames.size(); index++) {
            FlowValue value = exitState.get(parameterNames.get(index));
            if (value != null && value.isNotNull() && value.param() == index) {
                nonNullAfterCall.add(index);
            }
        }
        boolean hasStatements = !body.get().getStatements().isEmpty();
        return new FlowSummary(
                returned == null ? FlowValue.unknown() : returned,
                Set.copyOf(dereferencedParams),
                nonNullAfterCall,
                exitState == null && hasStatements,
                mutatesFields,
                Map.of());
    }

    // Поля объявившего метод класса. Поля volatile не отслеживаются: их меняют другие потоки
    private void initFields(CallableDeclaration<?> callable, FlowState state) {
        Optional<TypeDeclaration> type = callable.findAncestor(TypeDeclaration.class);
        if (type.isEmpty()) {
            return;
        }
        for (Object member : type.get().getMembers()) {
            if (!(member instanceof FieldDeclaration field) || field.isVolatile()) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                String key = THIS + variable.getNameAsString();
                FlowValue value = fieldValue(field, variable);
                rememberType(key, variable.getType());
                fields.put(key, value);
                state.assign(key, value);
            }
        }
    }

    private FlowValue fieldValue(FieldDeclaration field, VariableDeclarator variable) {
        FlowValue typed = typedUnknown(variable.getType());
        if (typed.numeric()) {
            return typed;
        }
        if (hasAnnotation(field.getAnnotations(), NULLABLE_ANNOTATIONS)) {
            return FlowValue.maybeNull("поле помечено @Nullable", variable);
        }
        if (hasAnnotation(field.getAnnotations(), NON_NULL_ANNOTATIONS)) {
            return FlowValue.notNull("поле помечено @NonNull", variable);
        }
        // final-поле, заданное при объявлении, другим уже не станет
        boolean isCreated = field.isFinal() && variable.getInitializer()
                .filter(initializer -> initializer.isObjectCreationExpr() || initializer.isArrayCreationExpr()
                        || initializer.isArrayInitializerExpr()
                        || initializer.isLiteralExpr() && !initializer.isNullLiteralExpr())
                .isPresent();
        return isCreated ? FlowValue.notNull("поле задано при объявлении", variable) : FlowValue.unknown();
    }

    private boolean hasAnnotation(NodeList<AnnotationExpr> annotations, Set<String> names) {
        return annotations.stream().anyMatch(annotation -> names.contains(annotation.getName().getIdentifier()));
    }

    // Вызванный метод мог изменить поля: о них известно только то, что было на входе
    private void flushFields(FlowState state) {
        fields.forEach(state::assign);
    }

    /**
     * @return имя, под которым значение выражения хранится в состоянии: локальная переменная, параметр
     * либо поле своего объекта (field и this.field); null - выражение не отслеживается
     */
    private String key(Expression expression, FlowState state) {
        Expression unwrapped = unwrap(expression);
        if (unwrapped instanceof NameExpr name) {
            String local = name.getNameAsString();
            if (state.has(local)) {
                return local;
            }
            return state.has(THIS + local) ? THIS + local : null;
        }
        if (unwrapped instanceof FieldAccessExpr access && access.getScope().isThisExpr()
                && state.has(THIS + access.getNameAsString())) {
            return THIS + access.getNameAsString();
        }
        return null;
    }

    private FlowValue parameterValue(Parameter parameter, int index) {
        String type = parameter.getType().asString();
        if (parameter.isVarArgs()) {
            return FlowValue.unknown();
        }
        if (INTEGRAL_TYPES.contains(type)) {
            return FlowValue.anyNumber();
        }
        if (BOOLEAN_TYPE.equals(type)) {
            return FlowValue.bool();
        }
        if (hasAnnotation(parameter.getAnnotations(), NON_NULL_ANNOTATIONS)) {
            return FlowValue.notNull("параметр помечен @NonNull", parameter);
        }
        boolean isNullable = hasAnnotation(parameter.getAnnotations(), NULLABLE_ANNOTATIONS);
        return isNullable
                ? FlowValue.maybeNull("параметр помечен @Nullable", parameter)
                : FlowValue.parameter(index);
    }

    // ---------------------------------------------------------------------------------------------- операторы

    /**
     * @return состояние после оператора либо null, если выполнение за него не проходит
     */
    private FlowState execute(Statement statement, FlowState state) {
        unreachableReported = false;
        terminated = false;
        if (statement instanceof BlockStmt block) {
            return executeAll(block.getStatements(), state);
        }
        if (statement instanceof ExpressionStmt expression) {
            evaluate(expression.getExpression(), state);
            return terminated ? null : state;
        }
        if (statement instanceof IfStmt ifStatement) {
            return executeIf(ifStatement, state);
        }
        if (statement instanceof ReturnStmt returnStatement) {
            return executeReturn(returnStatement, state);
        }
        if (statement instanceof ThrowStmt throwStatement) {
            evaluate(throwStatement.getExpression(), state);
            return null;
        }
        if (statement instanceof WhileStmt loop) {
            return executeLoop(loop, loop.getCondition(), loop.getBody(), List.of(), state);
        }
        if (statement instanceof ForStmt loop) {
            loop.getInitialization().forEach(initialization -> evaluate(initialization, state));
            return executeLoop(loop, loop.getCompare().orElse(null), loop.getBody(), loop.getUpdate(), state);
        }
        if (statement instanceof DoStmt loop) {
            return executeDoWhile(loop, state);
        }
        if (statement instanceof ForEachStmt loop) {
            return executeForEach(loop, state);
        }
        if (statement instanceof SwitchStmt switchStatement) {
            return executeSwitch(switchStatement, state);
        }
        if (statement instanceof TryStmt tryStatement) {
            return executeTry(tryStatement, state);
        }
        if (statement instanceof BreakStmt breakStatement) {
            breaks.add(new Break(breakStatement.getLabel().map(label -> label.asString()).orElse(null), state));
            return null;
        }
        if (statement instanceof ContinueStmt continueStatement) {
            continues.add(new Break(continueStatement.getLabel().map(label -> label.asString()).orElse(null), state));
            return null;
        }
        if (statement instanceof YieldStmt yield) {
            evaluate(yield.getExpression(), state);
            return null;
        }
        if (statement instanceof LabeledStmt labeled) {
            return executeLabeled(labeled, state);
        }
        if (statement instanceof SynchronizedStmt synchronizedStatement) {
            dereference(synchronizedStatement.getExpression(),
                    evaluate(synchronizedStatement.getExpression(), state), synchronizedStatement.getExpression(), state);
            return execute(synchronizedStatement.getBody(), state);
        }
        if (statement instanceof AssertStmt assertion) {
            quietConditions++;
            Branches branches = branch(assertion.getCheck(), state);
            quietConditions--;
            // Утверждения обычно выключены, поэтому путь "условие ложно" тоже продолжается
            return FlowState.join(branches.whenTrue(), branches.whenFalse());
        }
        if (statement instanceof ExplicitConstructorInvocationStmt invocation) {
            invocation.getArguments().forEach(argument -> evaluate(argument, state));
            return state;
        }
        // Объявление локального класса, пустой оператор и все незнакомое состояние не меняет
        return state;
    }

    private FlowState executeAll(List<Statement> statements, FlowState state) {
        FlowState current = state;
        for (Statement statement : statements) {
            if (current == null) {
                reportUnreachable(statement);
                return null;
            }
            current = execute(statement, current);
        }
        return current;
    }

    // Компилятор недостижимый код не пропустил бы: раз оператор здесь стоит, выполнение до него не дошло
    // из-за условия, которое всегда истинно, либо метода, который всегда бросает исключение.
    // Одинокий return или throw после такого метода ставят нарочно, чтобы код компилировался
    private void reportUnreachable(Statement statement) {
        boolean isPlaceholder = statement.isReturnStmt() || statement.isThrowStmt() || statement.isBreakStmt()
                || statement.isContinueStmt();
        if (unreachableReported || isPlaceholder || deathReason.isEmpty()) {
            return;
        }
        unreachableReported = true;
        report(FlowAnalysis.Kind.UNREACHABLE_CODE, statement,
                "Код недостижим: " + deathReason + ", и выполнение сюда не доходит;"
                        + " либо в условии ошибка, либо этот код лишний");
    }

    private FlowState executeIf(IfStmt statement, FlowState state) {
        Expression condition = statement.getCondition();
        Branches branches = branch(condition, state);
        conditionDepth++;
        FlowState thenEnd = branches.whenTrue() == null ? null : execute(statement.getThenStmt(), branches.whenTrue());
        FlowState elseEnd = branches.whenFalse();
        if (elseEnd != null && statement.getElseStmt().isPresent()) {
            elseEnd = execute(statement.getElseStmt().get(), elseEnd);
        }
        conditionDepth--;

        FlowState joined = FlowState.join(thenEnd, elseEnd);
        if (thenEnd != null && elseEnd != null && joined != thenEnd) {
            correlate(statement, thenEnd, elseEnd, joined);
        }
        return joined;
    }

    // Переменная получила значение только в одной ветке. Запоминаем, при каком условии это случилось:
    // под тем же условием (или тем же флагом) ниже по коду она уже не null
    private void correlate(IfStmt statement, FlowState thenEnd, FlowState elseEnd, FlowState joined) {
        Set<String> assigned = assignedIn(statement);
        Set<String> conditionNames = namesIn(statement.getCondition());
        boolean isConditionStable = conditionNames.stream().noneMatch(assigned::contains);
        String condition = statement.getCondition().toString();

        for (String name : new ArrayList<>(joined.names())) {
            FlowValue thenValue = thenEnd.get(name);
            FlowValue elseValue = elseEnd.get(name);
            if (thenValue == null || elseValue == null || thenValue.isNullish() == elseValue.isNullish()) {
                continue;
            }
            boolean isSetInThen = !thenValue.isNullish();
            FlowValue value = isSetInThen ? thenValue : elseValue;
            if (isConditionStable) {
                joined.correlate(new FlowState.Correlation(name, condition, isSetInThen, value, conditionNames));
            }
            // Флаг вида found = true, выставленный в той же ветке
            for (String flag : assigned) {
                FlowValue thenFlag = thenEnd.get(flag);
                FlowValue elseFlag = elseEnd.get(flag);
                boolean isFlag = thenFlag != null && elseFlag != null && thenFlag.isConstant() && elseFlag.isConstant()
                        && thenFlag.min() != elseFlag.min() && thenFlag.min() + elseFlag.min() == 1;
                if (isFlag && !flag.equals(name)) {
                    boolean flagWhenSet = (isSetInThen ? thenFlag : elseFlag).min() == 1;
                    joined.correlate(new FlowState.Correlation(name, flag, flagWhenSet, value, Set.of(flag)));
                }
            }
        }
    }

    private FlowState executeReturn(ReturnStmt statement, FlowState state) {
        FlowValue value = statement.getExpression().map(expression -> evaluate(expression, state)).orElse(null);
        if (lambdaDepth > 0) {
            return null;
        }
        // if (name == null) return null; - метод лишь отражает состояние поля. Что в поле, вызывающий код
        // знает лучше: для него такой метод равносилен геттеру, и о результате ничего утверждать нельзя
        if (value != null && value.isNullish() && isFieldCheckedForNull(state)) {
            value = FlowValue.unknown();
        }
        if (value != null) {
            // Причину показываем в месте вызова, поэтому привязываем ее к самому return
            FlowValue described = value.isNullish() && value.origin() == null ? value.withReason(value.reason(), statement) : value;
            returned = returned == null ? described : joinReturned(returned, described);
        }
        exitState = FlowState.join(exitState, state);
        return null;
    }

    private boolean isFieldCheckedForNull(FlowState state) {
        return fields.keySet().stream()
                .map(state::get)
                .anyMatch(value -> value != null && value.isNull() && CHECKED_FOR_NULL.equals(value.reason()));
    }

    // Метод, который где-то возвращает null, а где-то - неизвестно что, может вернуть null
    private FlowValue joinReturned(FlowValue first, FlowValue second) {
        return first.join(second);
    }

    private FlowState executeLoop(
            Statement loop, Expression condition, Statement body, List<Expression> update, FlowState state) {
        String label = labelOf(loop);
        FlowState entry = stabilize(loop, state, head -> {
            Branches branches = condition == null ? new Branches(head, null) : branch(condition, head);
            return branches.whenTrue() == null ? null : iterate(body, update, branches.whenTrue(), label);
        });

        int breaksBefore = breaks.size();
        Branches branches = condition == null ? new Branches(entry, null) : branch(condition, entry);
        if (branches.whenTrue() != null) {
            iterate(body, update, branches.whenTrue().copy(), label);
        }
        return FlowState.join(branches.whenFalse(), takeBreaks(breaksBefore, null));
    }

    private FlowState executeDoWhile(DoStmt loop, FlowState state) {
        String label = labelOf(loop);
        FlowState entry = stabilize(loop, state, head -> {
            FlowState end = iterate(loop.getBody(), List.of(), head, label);
            return end == null ? null : branch(loop.getCondition(), end).whenTrue();
        });

        int breaksBefore = breaks.size();
        FlowState end = iterate(loop.getBody(), List.of(), entry, label);
        FlowState exit = end == null ? null : branch(loop.getCondition(), end).whenFalse();
        return FlowState.join(exit, takeBreaks(breaksBefore, null));
    }

    private FlowState executeForEach(ForEachStmt loop, FlowState state) {
        Expression iterable = loop.getIterable();
        dereference(iterable, evaluate(iterable, state), iterable, state);
        String label = labelOf(loop);
        FlowState entry = stabilize(loop, state,
                head -> iterate(loop.getBody(), List.of(), declareLoopVariable(loop, head), label));

        int breaksBefore = breaks.size();
        iterate(loop.getBody(), List.of(), declareLoopVariable(loop, entry.copy()), label);
        // Цикл заканчивается либо сразу (элементов нет), либо после очередной итерации:
        // и то и другое - состояние в начале итерации
        return FlowState.join(entry, takeBreaks(breaksBefore, null));
    }

    private FlowState declareLoopVariable(ForEachStmt loop, FlowState state) {
        loop.getVariable().getVariables().forEach(variable -> {
            rememberType(variable.getNameAsString(), variable.getType());
            state.assign(variable.getNameAsString(), typedUnknown(variable.getType()));
        });
        return state;
    }

    /**
     * Ищет состояние в начале итерации, верное для любой итерации: проходит по телу цикла, сливает состояние
     * в конце тела с состоянием в начале и повторяет, пока оно меняется. Находки в этих проходах не записываются.
     *
     * @param iteration один проход: по состоянию в начале итерации возвращает состояние перед следующей
     *                  либо null, если следующей итерации не будет
     */
    private FlowState stabilize(Statement loop, FlowState before, UnaryOperator<FlowState> iteration) {
        FlowState entry = before.copy();
        int breaksBefore = breaks.size();
        int continuesBefore = continues.size();
        silent++;
        try {
            for (int pass = 0; pass < MAX_LOOP_PASSES; pass++) {
                FlowState back = iteration.apply(entry.copy());
                truncate(breaks, breaksBefore);
                truncate(continues, continuesBefore);
                if (back == null) {
                    return entry;
                }
                FlowState next = entry.widenedBy(back);
                if (next.sameAs(entry)) {
                    return entry;
                }
                entry = next;
            }
        } finally {
            silent--;
        }
        // Состояние так и не устоялось: о переменных, которые меняются в цикле, не утверждаем ничего
        entry.forget(assignedIn(loop), false, primitives);
        return entry;
    }

    /**
     * @return состояние после тела цикла и выражений обновления: с ним начнется следующая итерация
     */
    private FlowState iterate(Statement body, List<Expression> update, FlowState start, String label) {
        int continuesBefore = continues.size();
        conditionDepth++;
        FlowState end = execute(body, start);
        FlowState beforeUpdate = FlowState.join(end, takeContinues(continuesBefore, label));
        if (beforeUpdate != null) {
            update.forEach(expression -> evaluate(expression, beforeUpdate));
        }
        conditionDepth--;
        return beforeUpdate;
    }

    // continue без метки либо с меткой этого цикла
    private FlowState takeContinues(int from, String label) {
        FlowState joined = null;
        for (int index = continues.size() - 1; index >= from; index--) {
            Break jump = continues.get(index);
            if (jump.label() == null || jump.label().equals(label)) {
                joined = FlowState.join(joined, jump.state());
                continues.remove(index);
            }
        }
        return joined;
    }

    private void truncate(List<Break> jumps, int size) {
        while (jumps.size() > size) {
            jumps.remove(jumps.size() - 1);
        }
    }

    private String labelOf(Statement loop) {
        return loop.getParentNode()
                .filter(parent -> parent instanceof LabeledStmt)
                .map(parent -> ((LabeledStmt) parent).getLabel().asString())
                .orElse(null);
    }

    private FlowState executeLabeled(LabeledStmt labeled, FlowState state) {
        int breaksBefore = breaks.size();
        FlowState end = execute(labeled.getStatement(), state);
        return FlowState.join(end, takeBreaks(breaksBefore, labeled.getLabel().asString()));
    }

    /**
     * @param label null - break без метки; иначе только break с этой меткой
     * @return слияние состояний всех подходящих break, сделанных после отметки
     */
    private FlowState takeBreaks(int from, String label) {
        FlowState joined = null;
        for (int index = breaks.size() - 1; index >= from; index--) {
            Break exit = breaks.get(index);
            boolean matches = label == null ? exit.label() == null : label.equals(exit.label());
            if (matches) {
                joined = FlowState.join(joined, exit.state());
                breaks.remove(index);
            }
        }
        return joined;
    }

    private FlowState executeSwitch(SwitchStmt statement, FlowState state) {
        Expression selector = statement.getSelector();
        FlowValue selected = evaluate(selector, state);
        boolean handlesNull = statement.getEntries().stream()
                .flatMap(entry -> entry.getLabels().stream())
                .anyMatch(Expression::isNullLiteralExpr);
        if (!handlesNull) {
            dereference(selector, selected, selector, state);
        }

        int breaksBefore = breaks.size();
        FlowState exits = null;
        FlowState fallThrough = null;
        boolean hasDefault = false;
        conditionDepth++;
        for (SwitchEntry entry : statement.getEntries()) {
            hasDefault |= entry.getLabels().isEmpty();
            FlowState entryState = FlowState.join(state.copy(), fallThrough);
            FlowState end = executeAll(entry.getStatements(), entryState);
            if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
                fallThrough = end;
            } else {
                // Ветка со стрелкой в следующую не проваливается
                exits = FlowState.join(exits, end);
                fallThrough = null;
            }
        }
        conditionDepth--;
        exits = FlowState.join(exits, fallThrough);
        exits = FlowState.join(exits, takeBreaks(breaksBefore, null));
        if (!hasDefault) {
            // Ни одна ветка не подошла. Для enum такого пути может и не быть, поэтому о переменных,
            // которые задаются в ветках, здесь ничего не утверждаем
            FlowState unmatched = state.copy();
            unmatched.forget(assignedIn(statement), false, primitives);
            exits = FlowState.join(exits, unmatched);
        }
        return exits;
    }

    private FlowState executeTry(TryStmt statement, FlowState state) {
        FlowState before = state.copy();
        Set<String> assigned = assignedIn(statement.getTryBlock());
        statement.getResources().forEach(resource -> evaluate(resource, state));
        FlowState normal = execute(statement.getTryBlock(), state);

        conditionDepth++;
        for (CatchClause clause : statement.getCatchClauses()) {
            // Исключение могло случиться в любом месте try: до присваивания или после него
            FlowState catchState = before.copy();
            catchState.forget(assigned, true, primitives);
            catchState.assign(clause.getParameter().getNameAsString(),
                    FlowValue.notNull("пойманное исключение", clause.getParameter()));
            normal = FlowState.join(normal, execute(clause.getBody(), catchState));
            assigned.addAll(assignedIn(clause.getBody()));
        }
        if (statement.getFinallyBlock().isPresent()) {
            BlockStmt finallyBlock = statement.getFinallyBlock().get();
            FlowState finallyState = before.copy();
            finallyState.forget(assigned, true, primitives);
            FlowState finallyEnd = execute(finallyBlock, finallyState);
            if (finallyEnd == null) {
                normal = null;
            } else if (normal != null) {
                normal.forget(assignedIn(finallyBlock), false, primitives);
            }
        }
        conditionDepth--;
        return normal;
    }

    // -------------------------------------------------------------------------------------------- выражения

    /**
     * Вычисляет выражение: возвращает то, что известно о результате, и меняет состояние (присваивания, ++).
     * По пути проверяет обращения к значениям, которые могут быть null
     */
    private FlowValue evaluate(Expression expression, FlowState state) {
        if (expression instanceof EnclosedExpr enclosed) {
            return evaluate(enclosed.getInner(), state);
        }
        if (expression instanceof NullLiteralExpr) {
            return FlowValue.nullValue("задано как null", expression);
        }
        if (expression instanceof LiteralExpr literal) {
            return literalValue(literal);
        }
        if (expression instanceof NameExpr) {
            String variable = key(expression, state);
            return variable == null ? FlowValue.unknown() : state.get(variable);
        }
        if (expression instanceof VariableDeclarationExpr declaration) {
            declaration.getVariables().forEach(variable -> declare(variable, state));
            return FlowValue.unknown();
        }
        if (expression instanceof AssignExpr assignment) {
            return evaluateAssignment(assignment, state);
        }
        if (expression instanceof UnaryExpr unary) {
            return evaluateUnary(unary, state);
        }
        if (expression instanceof BinaryExpr binary) {
            return isLogical(binary) ? evaluateCondition(binary, state) : evaluateArithmetic(binary, state);
        }
        if (expression instanceof InstanceOfExpr) {
            return evaluateCondition(expression, state);
        }
        if (expression instanceof ConditionalExpr conditional) {
            return evaluateConditional(conditional, state);
        }
        if (expression instanceof MethodCallExpr call) {
            return evaluateCall(call, state);
        }
        if (expression instanceof FieldAccessExpr access) {
            return evaluateFieldAccess(access, state);
        }
        if (expression instanceof ArrayAccessExpr access) {
            return evaluateArrayAccess(access, state);
        }
        if (expression instanceof CastExpr cast) {
            FlowValue value = evaluate(cast.getExpression(), state);
            return INTEGRAL_TYPES.contains(cast.getType().asString()) && !value.numeric() ? FlowValue.anyNumber() : value;
        }
        if (expression instanceof ObjectCreationExpr creation) {
            creation.getScope().ifPresent(scope -> evaluate(scope, state));
            creation.getArguments().forEach(argument -> evaluate(argument, state));
            return FlowValue.notNull("объект создан через new", expression);
        }
        if (expression instanceof ArrayCreationExpr creation) {
            return evaluateArrayCreation(creation, state);
        }
        if (expression instanceof ArrayInitializerExpr initializer) {
            initializer.getValues().forEach(value -> evaluate(value, state));
            return FlowValue.array(initializer.getValues().size(), expression);
        }
        if (expression instanceof LambdaExpr lambda) {
            evaluateLambda(lambda, state);
            return FlowValue.notNull("лямбда", expression);
        }
        if (expression instanceof MethodReferenceExpr reference) {
            // user::getName вычисляет user сразу, в месте создания ссылки
            Expression scope = reference.getScope();
            if (key(scope, state) != null) {
                dereference(scope, evaluate(scope, state), reference, state);
            }
            return FlowValue.notNull("ссылка на метод", expression);
        }
        if (expression instanceof SwitchExpr switchExpression) {
            return evaluateSwitch(switchExpression, state);
        }
        if (expression.isThisExpr() || expression.isClassExpr() || expression.isSuperExpr()) {
            return FlowValue.notNull("ссылка на объект", expression);
        }
        return FlowValue.unknown();
    }

    private FlowValue literalValue(LiteralExpr literal) {
        try {
            if (literal instanceof IntegerLiteralExpr integer) {
                long value = integer.asNumber().longValue();
                return FlowValue.number(value, value);
            }
            if (literal instanceof LongLiteralExpr longLiteral) {
                long value = longLiteral.asNumber().longValue();
                return FlowValue.number(value, value);
            }
            if (literal instanceof CharLiteralExpr character) {
                return FlowValue.number(character.asChar(), character.asChar());
            }
        } catch (RuntimeException exception) {
            // Запись числа, которую JavaParser не разобрал: значение неизвестно
            return FlowValue.anyNumber();
        }
        if (literal instanceof BooleanLiteralExpr bool) {
            return bool.getValue() ? FlowValue.number(1, 1) : FlowValue.number(0, 0);
        }
        return FlowValue.notNull("литерал", literal);
    }

    private void declare(VariableDeclarator variable, FlowState state) {
        rememberType(variable.getNameAsString(), variable.getType());
        FlowValue typed = typedUnknown(variable.getType());
        FlowValue value = variable.getInitializer().map(initializer -> evaluate(initializer, state)).orElse(typed);
        state.assign(variable.getNameAsString(), assigned(value, typed, variable));
        variable.getInitializer().ifPresent(initializer -> correlateFlag(variable.getNameAsString(), initializer, state));
    }

    // boolean isOwner = user != null && user.isOwner(): под условием if (isOwner) переменная user не null.
    // Запоминаем, что о каких переменных известно при истинном и при ложном значении флага
    private void correlateFlag(String flag, Expression value, FlowState state) {
        Expression condition = unwrap(value);
        boolean isCondition = condition instanceof InstanceOfExpr
                || condition instanceof BinaryExpr binary && isLogical(binary)
                || condition instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT;
        Branches branches = lastCondition;
        if (!isCondition || !primitives.contains(flag) || branches == null
                || branches.whenTrue() == null || branches.whenFalse() == null) {
            return;
        }
        for (String name : new ArrayList<>(state.names())) {
            FlowValue whenTrue = branches.whenTrue().get(name);
            FlowValue whenFalse = branches.whenFalse().get(name);
            if (name.equals(flag) || whenTrue == null || whenFalse == null || whenTrue.isNullish() == whenFalse.isNullish()) {
                continue;
            }
            boolean isSetWhenTrue = !whenTrue.isNullish();
            state.correlate(new FlowState.Correlation(
                    name, flag, isSetWhenTrue, isSetWhenTrue ? whenTrue : whenFalse, Set.of(flag)));
        }
    }

    private void rememberType(String name, Type type) {
        String typeName = type.asString();
        if (INTEGRAL_TYPES.contains(typeName) || BOOLEAN_TYPE.equals(typeName)) {
            primitives.add(name);
        } else {
            primitives.remove(name);
        }
    }

    // Значение, о котором известен только тип: у чисел это полный диапазон, у boolean - false либо true
    private FlowValue typedUnknown(Type type) {
        String name = type.asString();
        if (INTEGRAL_TYPES.contains(name)) {
            return FlowValue.anyNumber();
        }
        return BOOLEAN_TYPE.equals(name) ? FlowValue.bool() : FlowValue.unknown();
    }

    // Значение в том виде, в каком оно хранится в переменной: число остается числом, даже если
    // справа стоит вызов с неизвестным результатом; у null запоминается место присваивания
    private FlowValue assigned(FlowValue value, FlowValue typed, Node place) {
        if (typed.numeric() && !value.numeric()) {
            return typed;
        }
        if (value.isNull() && value.origin() instanceof NullLiteralExpr) {
            return value.withReason("переменной присвоен null", place);
        }
        return value;
    }

    private FlowValue evaluateAssignment(AssignExpr assignment, FlowState state) {
        Expression target = assignment.getTarget();
        String name = key(target, state);
        if (name == null) {
            // Поле чужого объекта или элемент массива: само присваивание не отслеживается, но обе части вычисляются
            if (!(target instanceof NameExpr)) {
                evaluate(target, state);
            }
            return evaluate(assignment.getValue(), state);
        }

        mutatesFields |= name.startsWith(THIS);
        FlowValue current = state.get(name);
        FlowValue value = evaluate(assignment.getValue(), state);
        FlowValue result;
        if (assignment.getOperator() == AssignExpr.Operator.ASSIGN) {
            result = assigned(value, primitives.contains(name) ? numberLike(current) : FlowValue.unknown(), assignment);
        } else {
            BinaryExpr.Operator operator = assignment.getOperator().toBinaryOperator().orElse(null);
            result = operator == null ? FlowValue.unknown() : arithmetic(operator, current, value, assignment);
            if (primitives.contains(name) && !result.numeric()) {
                result = FlowValue.anyNumber();
            }
        }
        state.assign(name, result);
        if (assignment.getOperator() == AssignExpr.Operator.ASSIGN) {
            correlateFlag(name, assignment.getValue(), state);
        }
        return result;
    }

    private FlowValue numberLike(FlowValue value) {
        return value.min() >= 0 && value.max() <= 1 ? FlowValue.bool() : FlowValue.anyNumber();
    }

    private FlowValue evaluateUnary(UnaryExpr unary, FlowState state) {
        Expression inner = unary.getExpression();
        switch (unary.getOperator()) {
            case LOGICAL_COMPLEMENT:
                return evaluateCondition(unary, state);
            case MINUS: {
                FlowValue value = evaluate(inner, state);
                return value.numeric() ? FlowValue.number(-value.max(), -value.min()) : value;
            }
            case PLUS:
                return evaluate(inner, state);
            case PREFIX_INCREMENT:
            case POSTFIX_INCREMENT:
                return increment(unary, inner, 1, state);
            case PREFIX_DECREMENT:
            case POSTFIX_DECREMENT:
                return increment(unary, inner, -1, state);
            default:
                evaluate(inner, state);
                return FlowValue.anyNumber();
        }
    }

    private FlowValue increment(UnaryExpr unary, Expression target, int delta, FlowState state) {
        FlowValue before = evaluate(target, state);
        String name = key(target, state);
        if (name == null || !before.numeric()) {
            return before.numeric() ? FlowValue.anyNumber() : FlowValue.unknown();
        }
        FlowValue after = before.isBounded()
                ? FlowValue.number(before.min() == FlowValue.MIN ? FlowValue.MIN : before.min() + delta,
                        before.max() == FlowValue.MAX ? FlowValue.MAX : before.max() + delta)
                : FlowValue.anyNumber();
        mutatesFields |= name.startsWith(THIS);
        state.assign(name, primitives.contains(name) ? after : FlowValue.unknown());
        return unary.isPostfix() ? before : after;
    }

    private boolean isLogical(BinaryExpr binary) {
        return switch (binary.getOperator()) {
            case AND, OR, EQUALS, NOT_EQUALS, LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> true;
            default -> false;
        };
    }

    // Условие вне if - в return, в присваивании, в аргументе: обе ветки сливаются, а результат известен,
    // если одна из них невозможна
    private FlowValue evaluateCondition(Expression condition, FlowState state) {
        Branches branches = branch(condition, state.copy());
        lastCondition = branches;
        FlowState joined = FlowState.join(branches.whenTrue(), branches.whenFalse());
        if (joined != null) {
            state.adopt(joined);
        }
        if (branches.whenFalse() == null) {
            return FlowValue.number(1, 1);
        }
        return branches.whenTrue() == null ? FlowValue.number(0, 0) : FlowValue.bool();
    }

    private FlowValue evaluateArithmetic(BinaryExpr binary, FlowState state) {
        FlowValue left = evaluate(binary.getLeft(), state);
        FlowValue right = evaluate(binary.getRight(), state);
        return arithmetic(binary.getOperator(), left, right, binary);
    }

    private FlowValue arithmetic(BinaryExpr.Operator operator, FlowValue left, FlowValue right, Node place) {
        if (!left.numeric() || !right.numeric()) {
            // Сложение со строкой либо число неизвестного вида: результат в любом случае не null
            return FlowValue.notNull("результат выражения", place);
        }
        switch (operator) {
            case PLUS:
                return left.isBounded() && right.isBounded()
                        ? FlowValue.number(left.min() + right.min(), left.max() + right.max())
                        : shifted(left, right);
            case MINUS:
                return left.isBounded() && right.isBounded()
                        ? FlowValue.number(left.min() - right.max(), left.max() - right.min())
                        : shifted(left, new FlowValue(right.nullness(), true, -right.max(), -right.min(),
                                FlowValue.NO_PARAM, FlowValue.NO_LENGTH, "", null));
            case MULTIPLY:
                return multiply(left, right);
            case DIVIDE:
            case REMAINDER:
                if (right.isConstant(0)) {
                    report(FlowAnalysis.Kind.DIVISION_BY_ZERO, place,
                            "Делитель здесь всегда равен нулю: выполнение завершится ArithmeticException;"
                                    + " проверьте делитель перед делением");
                    return FlowValue.anyNumber();
                }
                return operator == BinaryExpr.Operator.DIVIDE ? divide(left, right) : remainder(left, right);
            default:
                return FlowValue.anyNumber();
        }
    }

    // indexOf(...) + 1: нижняя граница известна, верхняя - нет
    private FlowValue shifted(FlowValue left, FlowValue right) {
        if (right.isConstant() && left.min() > FlowValue.MIN && left.max() == FlowValue.MAX) {
            return FlowValue.number(left.min() + right.min(), FlowValue.MAX);
        }
        if (left.isConstant() && right.min() > FlowValue.MIN && right.max() == FlowValue.MAX) {
            return FlowValue.number(left.min() + right.min(), FlowValue.MAX);
        }
        return FlowValue.anyNumber();
    }

    private FlowValue multiply(FlowValue left, FlowValue right) {
        if (!left.isBounded() || !right.isBounded() || left.min() == FlowValue.MIN || right.min() == FlowValue.MIN
                || left.max() == FlowValue.MAX || right.max() == FlowValue.MAX) {
            return FlowValue.anyNumber();
        }
        long[] products = {
            left.min() * right.min(), left.min() * right.max(), left.max() * right.min(), left.max() * right.max()
        };
        long min = products[0];
        long max = products[0];
        for (long product : products) {
            min = Math.min(min, product);
            max = Math.max(max, product);
        }
        return FlowValue.number(min, max);
    }

    private FlowValue divide(FlowValue left, FlowValue right) {
        if (!right.isConstant() || !left.isBounded() || left.min() == FlowValue.MIN || left.max() == FlowValue.MAX) {
            return FlowValue.anyNumber();
        }
        long first = left.min() / right.min();
        long second = left.max() / right.min();
        return FlowValue.number(Math.min(first, second), Math.max(first, second));
    }

    private FlowValue remainder(FlowValue left, FlowValue right) {
        if (!right.isConstant()) {
            return FlowValue.anyNumber();
        }
        long bound = Math.abs(right.min()) - 1;
        return FlowValue.number(left.min() >= 0 ? 0 : -bound, bound);
    }

    private FlowValue evaluateConditional(ConditionalExpr conditional, FlowState state) {
        Branches branches = branch(conditional.getCondition(), state.copy());
        conditionDepth++;
        FlowValue thenValue = branches.whenTrue() == null ? null : evaluate(conditional.getThenExpr(), branches.whenTrue());
        FlowValue elseValue = branches.whenFalse() == null ? null : evaluate(conditional.getElseExpr(), branches.whenFalse());
        conditionDepth--;

        FlowState joined = FlowState.join(branches.whenTrue(), branches.whenFalse());
        if (joined != null) {
            state.adopt(joined);
        }
        if (thenValue == null) {
            return elseValue == null ? FlowValue.unknown() : elseValue;
        }
        return elseValue == null ? thenValue : thenValue.join(elseValue);
    }

    private FlowValue evaluateFieldAccess(FieldAccessExpr access, FlowState state) {
        String field = key(access, state);
        if (field != null) {
            return state.get(field);
        }
        Expression scope = access.getScope();
        FlowValue owner = evaluate(scope, state);
        dereference(scope, owner, access, state);
        if (LENGTH.equals(access.getNameAsString())) {
            return owner.length() == FlowValue.NO_LENGTH
                    ? FlowValue.number(0, FlowValue.MAX)
                    : FlowValue.number(owner.length(), owner.length());
        }
        return known(access, FlowValue.unknown(), state);
    }

    private FlowValue evaluateArrayAccess(ArrayAccessExpr access, FlowState state) {
        FlowValue array = evaluate(access.getName(), state);
        dereference(access.getName(), array, access, state);
        FlowValue index = evaluate(access.getIndex(), state);
        checkIndex(index, array.length(), access);
        return FlowValue.unknown();
    }

    /**
     * @param length длина массива либо NO_LENGTH, если она неизвестна
     */
    private void checkIndex(FlowValue index, long length, Node place) {
        if (!index.numeric()) {
            return;
        }
        if (index.max() < 0) {
            report(FlowAnalysis.Kind.INDEX_OUT_OF_BOUNDS, place,
                    "Индекс здесь всегда отрицательный (" + range(index) + "): обращение завершится исключением");
        } else if (index.min() == -1 && INDEX_SEARCH_REASON.equals(index.reason()) && !isSearchGuarded(place)) {
            report(FlowAnalysis.Kind.INDEX_OUT_OF_BOUNDS, place,
                    "Индекс - " + INDEX_SEARCH_REASON + " (indexOf), а он равен -1, когда ничего не найдено:"
                            + " обращение завершится исключением; сначала проверьте, что индекс не отрицательный");
        } else if (length != FlowValue.NO_LENGTH && index.max() < FlowValue.MAX && index.max() >= length) {
            report(FlowAnalysis.Kind.INDEX_OUT_OF_BOUNDS, place,
                    "Индекс может достигать " + index.max() + ", а длина массива - " + length
                            + ": допустимы индексы от 0 до " + (length - 1) + "; проверьте границу, обычно это <= вместо <");
        }
    }

    // if (text.contains(":")) { text.substring(text.indexOf(':')) }: наличие искомого уже проверено
    private boolean isSearchGuarded(Node place) {
        Node current = place.getParentNode().orElse(null);
        while (current != null && !(current instanceof CallableDeclaration)) {
            String condition = "";
            if (current instanceof IfStmt statement) {
                condition = statement.getCondition().toString();
            } else if (current instanceof ConditionalExpr conditional) {
                condition = conditional.getCondition().toString();
            }
            if (SEARCH_GUARDS.stream().anyMatch(condition::contains)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private String range(FlowValue value) {
        if (value.isConstant()) {
            return String.valueOf(value.min());
        }
        return (value.min() == FlowValue.MIN ? "..." : String.valueOf(value.min())) + " - "
                + (value.max() == FlowValue.MAX ? "..." : String.valueOf(value.max()));
    }

    private FlowValue evaluateArrayCreation(ArrayCreationExpr creation, FlowState state) {
        if (creation.getInitializer().isPresent()) {
            return evaluate(creation.getInitializer().get(), state).withReason("массив создан", creation);
        }
        long length = FlowValue.NO_LENGTH;
        for (int level = 0; level < creation.getLevels().size(); level++) {
            Optional<Expression> dimension = creation.getLevels().get(level).getDimension();
            if (dimension.isPresent()) {
                FlowValue size = evaluate(dimension.get(), state);
                if (level == 0 && size.isConstant() && size.min() >= 0) {
                    length = size.min();
                }
            }
        }
        return FlowValue.array(length, creation);
    }

    // Захваченные переменные неизменны, поэтому внутри лямбды о них известно то же, что и снаружи.
    // Что лямбда узнала сама (проверки, обращения), наружу не выходит: неизвестно, когда она выполнится
    private void evaluateLambda(LambdaExpr lambda, FlowState state) {
        FlowState inner = state.copy();
        // Лямбда выполнится неизвестно когда: поля к тому времени могут измениться
        flushFields(inner);
        lambda.getParameters().forEach(parameter -> inner.assign(parameter.getNameAsString(), FlowValue.unknown()));
        int breaksBefore = breaks.size();
        boolean wasTerminated = terminated;
        conditionDepth++;
        lambdaDepth++;
        if (lambda.getBody() instanceof ExpressionStmt expression) {
            evaluate(expression.getExpression(), inner);
        } else {
            execute(lambda.getBody(), inner);
        }
        lambdaDepth--;
        conditionDepth--;
        terminated = wasTerminated;
        takeBreaks(breaksBefore, null);
    }

    private FlowValue evaluateSwitch(SwitchExpr switchExpression, FlowState state) {
        Expression selector = switchExpression.getSelector();
        FlowValue selected = evaluate(selector, state);
        boolean handlesNull = switchExpression.getEntries().stream()
                .flatMap(entry -> entry.getLabels().stream())
                .anyMatch(Expression::isNullLiteralExpr);
        if (!handlesNull) {
            dereference(selector, selected, selector, state);
        }
        int breaksBefore = breaks.size();
        boolean wasTerminated = terminated;
        conditionDepth++;
        for (SwitchEntry entry : switchExpression.getEntries()) {
            executeAll(entry.getStatements(), state.copy());
        }
        conditionDepth--;
        terminated = wasTerminated;
        takeBreaks(breaksBefore, null);
        state.forget(assignedIn(switchExpression), false, primitives);
        return FlowValue.unknown();
    }

    // ------------------------------------------------------------------------------------------------ вызовы

    private FlowValue evaluateCall(MethodCallExpr call, FlowState state) {
        String method = call.getNameAsString();
        if (call.getScope().isPresent()) {
            Expression scope = call.getScope().get();
            dereference(scope, evaluate(scope, state), call, state);
        }
        List<FlowValue> arguments = new ArrayList<>();
        for (Expression argument : call.getArguments()) {
            arguments.add(evaluate(argument, state));
        }

        Optional<FlowSummary> summary = host.summaryOf(call);
        FlowValue result = summary.isPresent()
                ? applySummary(call, summary.get(), arguments, state)
                : libraryCall(call, method, arguments, state);

        // Метод своего объекта мог изменить поля - если только по его сводке не видно обратное
        boolean isOwn = call.getScope().map(scope -> scope.isThisExpr() || scope.isSuperExpr()).orElse(true);
        boolean passesThis = call.getArguments().stream().anyMatch(Expression::isThisExpr);
        boolean keepsFields = summary.isPresent() && !summary.get().mutatesFields();
        if ((isOwn || passesThis) && !keepsFields) {
            flushFields(state);
            mutatesFields = true;
        }
        return known(call, result, state);
    }

    // То же выражение уже проверено на null либо к нему уже обращались: user.getName() после
    // if (user.getName() != null). Считаем, что между проверкой и использованием значение не менялось
    private FlowValue known(Expression expression, FlowValue value, FlowState state) {
        FlowValue fact = value.isNotNull() || value.numeric() ? null : state.fact(expression.toString());
        return fact == null ? value : fact;
    }

    private FlowValue applySummary(MethodCallExpr call, FlowSummary summary, List<FlowValue> arguments, FlowState state) {
        String method = call.getNameAsString();
        for (int index : summary.dereferencedParams()) {
            FlowValue argument = arguments.get(index);
            Expression expression = call.getArgument(index);
            if (argument.isNull()) {
                report(FlowAnalysis.Kind.NULL_ARGUMENT, expression,
                        "В метод '" + method + "' передается null (" + describe(expression, argument) + "), а метод"
                                + " обращается к этому параметру без проверки: вызов завершится NullPointerException");
            } else if (argument.isNullish()) {
                report(FlowAnalysis.Kind.NULL_ARGUMENT, expression,
                        "В метод '" + method + "' передается значение, которое может быть null ("
                                + describe(expression, argument) + "), а метод обращается к этому параметру без"
                                + " проверки; проверьте значение перед вызовом");
            } else if (argument.nullness() == FlowValue.Nullness.UNKNOWN && argument.param() >= 0 && conditionDepth == 0) {
                // Параметр уходит дальше в метод, который к нему обращается: обращение считается и нашим
                dereferencedParams.add(argument.param());
            }
        }
        for (int index : summary.nonNullAfterCall()) {
            Expression expression = call.getArgument(index);
            String variable = key(expression, state);
            if (variable != null) {
                state.refine(variable, state.get(variable).withNullness(
                        FlowValue.Nullness.NOT_NULL, "метод '" + method + "' не вернул бы управление, будь значение null", call));
            }
        }
        if (summary.neverReturns()) {
            terminate("метод '" + method + "' на строке " + lineOf(call) + " не возвращает управление: он всегда бросает исключение");
        }

        FlowValue returned = summary.returned();
        if (returned.isNullish()) {
            if (returned.origin() instanceof MethodDeclaration) {
                return FlowValue.maybeNull("метод '" + method + "' помечен @Nullable", call);
            }
            int line = returned.line();
            return FlowValue.maybeNull(
                    "метод '" + method + "' может вернуть null" + (line > 0 ? " (строка " + line + " в его файле)" : ""), call);
        }
        if (returned.numeric()) {
            return returned.isBounded() ? FlowValue.number(returned.min(), returned.max()) : FlowValue.anyNumber();
        }
        return returned.isNotNull() ? FlowValue.notNull("метод '" + method + "' не возвращает null", call) : FlowValue.unknown();
    }

    // Методы JDK и библиотек, поведение которых известно заранее
    private FlowValue libraryCall(MethodCallExpr call, String method, List<FlowValue> arguments, FlowState state) {
        if (NULL_ASSERTIONS.contains(method) && isStaticStyle(call, state) && !arguments.isEmpty()) {
            Expression first = call.getArgument(0);
            String variable = key(first, state);
            if (variable != null) {
                state.refine(variable, arguments.get(0).withNullness(
                        FlowValue.Nullness.NOT_NULL, "значение проверено вызовом " + method, call));
            }
            return FlowValue.notNull("значение проверено вызовом " + method, call);
        }
        if (NEVER_RETURNING.contains(method) && isStaticStyle(call, state)) {
            terminate("вызов '" + method + "' на строке " + lineOf(call) + " не возвращает управление");
            return FlowValue.unknown();
        }
        if (OR_ELSE.equals(method) && arguments.size() == 1 && arguments.get(0).isNull()) {
            return FlowValue.maybeNull("orElse(null) возвращает null, когда значения нет", call);
        }
        if (INDEX_SEARCH.contains(method) && call.getScope().isPresent()) {
            return FlowValue.number(-1, FlowValue.MAX).withReason(INDEX_SEARCH_REASON, call);
        }
        if (SIZE_METHODS.contains(method) && arguments.isEmpty() && call.getScope().isPresent()) {
            return FlowValue.number(0, FlowValue.MAX);
        }
        if (INDEX_CONSUMERS.contains(method) && !arguments.isEmpty() && call.getScope().isPresent()) {
            arguments.forEach(argument -> checkIndex(argument, FlowValue.NO_LENGTH, call));
        }
        return FlowValue.unknown();
    }

    // Objects.requireNonNull(x) либо статически импортированный requireNonNull(x), но не list.notEmpty(x)
    private boolean isStaticStyle(MethodCallExpr call, FlowState state) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return true;
        }
        return scope.get() instanceof NameExpr name && !state.has(name.getNameAsString())
                && Character.isUpperCase(name.getNameAsString().charAt(0));
    }

    private void terminate(String reason) {
        terminated = true;
        deathReason = reason;
    }

    // ------------------------------------------------------------------------------------- обращение к null

    /**
     * @param target выражение, к значению которого обращаются: user в user.getName()
     * @param place  само обращение - на него показывает находка
     */
    private void dereference(Expression target, FlowValue value, Node place, FlowState state) {
        Expression unwrapped = unwrap(target);
        if (value.isNull()) {
            // Проверка и обращение в одном условии (x == null && x.isEmpty()) - предмет отдельного правила
            if (!isInSameCondition(value.origin(), place)) {
                report(FlowAnalysis.Kind.NULL_DEREFERENCE, place,
                        "'" + snippet(unwrapped) + "' здесь всегда null (" + describe(unwrapped, value) + "): обращение '"
                                + snippet(place) + "' завершится NullPointerException");
            }
        } else if (value.nullness() == FlowValue.Nullness.MAYBE_NULL) {
            report(FlowAnalysis.Kind.POSSIBLE_NULL_DEREFERENCE, place,
                    "'" + snippet(unwrapped) + "' может быть null (" + describe(unwrapped, value) + "), а обращение '"
                            + snippet(place) + "' идет без проверки; добавьте проверку либо обработайте отсутствие значения");
        } else if (value.nullness() == FlowValue.Nullness.UNKNOWN && value.param() >= 0 && conditionDepth == 0) {
            dereferencedParams.add(value.param());
        }

        // После обращения значение не null: иначе выполнение сюда бы не дошло. Заодно это убирает повторы
        String variable = key(unwrapped, state);
        if (variable != null) {
            if (!value.isNotNull()) {
                state.refine(variable, value.withNullness(
                        FlowValue.Nullness.NOT_NULL, "к значению уже обращались", place));
            }
        } else if (value.isNullish() && (unwrapped instanceof MethodCallExpr || unwrapped instanceof FieldAccessExpr)) {
            state.learn(unwrapped.toString(), value.withNullness(
                    FlowValue.Nullness.NOT_NULL, "к значению уже обращались", place), namesIn(unwrapped));
        }
    }

    private boolean isInSameCondition(Node origin, Node place) {
        Node root = origin;
        while (root != null && root.getParentNode().orElse(null) instanceof Expression parent) {
            root = parent;
        }
        return root != null && root.isAncestorOf(place);
    }

    private String describe(Expression expression, FlowValue value) {
        int line = value.line();
        String where = line > 0 && !(value.origin() instanceof MethodCallExpr && value.origin() == expression)
                ? " на строке " + line
                : "";
        return value.reason().isEmpty() ? "причина неизвестна" : value.reason() + where;
    }

    // --------------------------------------------------------------------------------------------- условия

    /**
     * Разбирает условие: возвращает состояния для случаев "истинно" и "ложно", уточненные самим условием.
     * Если один из исходов невозможен, условие всегда истинно либо всегда ложно - об этом сообщается
     */
    private Branches branch(Expression rawCondition, FlowState state) {
        Expression condition = unwrap(rawCondition);
        Branches branches = split(condition, state);
        String text = condition.toString();
        if (branches.whenTrue() != null) {
            branches.whenTrue().applyCondition(text, true);
        }
        if (branches.whenFalse() != null) {
            branches.whenFalse().applyCondition(text, false);
        }
        return branches;
    }

    private Branches split(Expression condition, FlowState state) {
        if (condition instanceof BooleanLiteralExpr literal) {
            return literal.getValue() ? new Branches(state, null) : new Branches(null, state);
        }
        if (condition instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return branch(unary.getExpression(), state).swapped();
        }
        if (condition instanceof BinaryExpr binary) {
            return splitBinary(binary, state);
        }
        if (condition instanceof InstanceOfExpr instanceOf) {
            return splitInstanceOf(instanceOf, state);
        }
        if (condition instanceof MethodCallExpr call) {
            return splitCall(call, state);
        }
        if ((condition instanceof NameExpr || condition instanceof FieldAccessExpr) && key(condition, state) != null) {
            return splitFlag(key(condition, state), condition, state);
        }
        FlowValue value = evaluate(condition, state);
        if (value.isConstant(1)) {
            return new Branches(state, null);
        }
        return value.isConstant(0) ? new Branches(null, state) : new Branches(state.copy(), state);
    }

    private Branches splitBinary(BinaryExpr binary, FlowState state) {
        switch (binary.getOperator()) {
            case AND: {
                Branches left = branch(binary.getLeft(), state);
                if (left.whenTrue() == null) {
                    return left;
                }
                conditionDepth++;
                Branches right = branch(binary.getRight(), left.whenTrue());
                conditionDepth--;
                return new Branches(right.whenTrue(), FlowState.join(left.whenFalse(), right.whenFalse()));
            }
            case OR: {
                Branches left = branch(binary.getLeft(), state);
                if (left.whenFalse() == null) {
                    return left;
                }
                conditionDepth++;
                Branches right = branch(binary.getRight(), left.whenFalse());
                conditionDepth--;
                return new Branches(FlowState.join(left.whenTrue(), right.whenTrue()), right.whenFalse());
            }
            case EQUALS:
            case NOT_EQUALS: {
                boolean isEquals = binary.getOperator() == BinaryExpr.Operator.EQUALS;
                Expression left = unwrap(binary.getLeft());
                Expression right = unwrap(binary.getRight());
                if (left.isNullLiteralExpr() || right.isNullLiteralExpr()) {
                    Branches branches = splitNullCheck(binary, left.isNullLiteralExpr() ? right : left, state);
                    return isEquals ? branches : branches.swapped();
                }
                return splitComparison(binary, state);
            }
            case LESS:
            case LESS_EQUALS:
            case GREATER:
            case GREATER_EQUALS:
                return splitComparison(binary, state);
            default: {
                evaluate(binary, state);
                return new Branches(state.copy(), state);
            }
        }
    }

    /**
     * @return состояния для "значение равно null" и "значение не равно null"
     */
    private Branches splitNullCheck(BinaryExpr check, Expression checked, FlowState state) {
        FlowValue value = evaluate(checked, state);
        if (value.isNull()) {
            reportConstant(check, check.getOperator() == BinaryExpr.Operator.EQUALS,
                    "'" + snippet(checked) + "' здесь всегда null: " + describe(checked, value));
            return new Branches(state, null);
        }
        if (value.isNotNull()) {
            reportConstant(check, check.getOperator() != BinaryExpr.Operator.EQUALS,
                    "'" + snippet(checked) + "' здесь не может быть null: " + describe(checked, value));
            return new Branches(null, state);
        }
        String variable = key(checked, state);
        if (variable == null) {
            FlowState whenNull = state.copy();
            if (checked instanceof MethodCallExpr || checked instanceof FieldAccessExpr) {
                state.learn(checked.toString(), value.withNullness(
                        FlowValue.Nullness.NOT_NULL, "значение уже проверено на null", check), namesIn(checked));
            }
            return new Branches(whenNull, state);
        }
        FlowState whenNull = state.copy();
        whenNull.refine(variable,
                value.withNullness(FlowValue.Nullness.NULL, CHECKED_FOR_NULL, check));
        state.refine(variable,
                value.withNullness(FlowValue.Nullness.NOT_NULL, "значение уже проверено на null", check));
        return new Branches(whenNull, state);
    }

    private Branches splitComparison(BinaryExpr comparison, FlowState state) {
        Expression leftExpression = unwrap(comparison.getLeft());
        Expression rightExpression = unwrap(comparison.getRight());
        FlowValue left = evaluate(leftExpression, state);
        FlowValue right = evaluate(rightExpression, state);
        if (!left.numeric() || !right.numeric()) {
            return new Branches(state.copy(), state);
        }

        long[] whenTrue = refine(comparison.getOperator(), left, right);
        long[] whenFalse = refine(negate(comparison.getOperator()), left, right);
        if (whenTrue == null || whenFalse == null) {
            reportConstant(comparison, whenFalse == null,
                    "'" + snippet(leftExpression) + "' здесь " + describeRange(left)
                            + ", '" + snippet(rightExpression) + "' - " + describeRange(right));
        }
        FlowState falseState = whenFalse == null ? null : (whenTrue == null ? state : state.copy());
        FlowState trueState = whenTrue == null ? null : state;
        narrow(trueState, leftExpression, left, rightExpression, right, whenTrue);
        narrow(falseState, leftExpression, left, rightExpression, right, whenFalse);
        return new Branches(trueState, falseState);
    }

    private void narrow(
            FlowState state, Expression leftExpression, FlowValue left, Expression rightExpression, FlowValue right,
            long[] bounds) {
        if (state == null) {
            return;
        }
        String leftVariable = key(leftExpression, state);
        if (leftVariable != null) {
            state.refine(leftVariable, left.withRange(bounds[0], bounds[1]));
        }
        String rightVariable = key(rightExpression, state);
        if (rightVariable != null) {
            state.refine(rightVariable, right.withRange(bounds[2], bounds[3]));
        }
    }

    private String describeRange(FlowValue value) {
        return value.isConstant() ? "всегда равно " + value.min() : "в диапазоне " + range(value);
    }

    private BinaryExpr.Operator negate(BinaryExpr.Operator operator) {
        return switch (operator) {
            case EQUALS -> BinaryExpr.Operator.NOT_EQUALS;
            case NOT_EQUALS -> BinaryExpr.Operator.EQUALS;
            case LESS -> BinaryExpr.Operator.GREATER_EQUALS;
            case LESS_EQUALS -> BinaryExpr.Operator.GREATER;
            case GREATER -> BinaryExpr.Operator.LESS_EQUALS;
            default -> BinaryExpr.Operator.LESS;
        };
    }

    /**
     * @return диапазоны обеих сторон, при которых сравнение истинно: {левый минимум, левый максимум,
     * правый минимум, правый максимум}; null, если сравнение истинным быть не может
     */
    private long[] refine(BinaryExpr.Operator operator, FlowValue left, FlowValue right) {
        long leftMin = left.min();
        long leftMax = left.max();
        long rightMin = right.min();
        long rightMax = right.max();
        switch (operator) {
            case LESS:
                leftMax = Math.min(leftMax, rightMax - 1);
                rightMin = Math.max(rightMin, leftMin + 1);
                break;
            case LESS_EQUALS:
                leftMax = Math.min(leftMax, rightMax);
                rightMin = Math.max(rightMin, leftMin);
                break;
            case GREATER:
                leftMin = Math.max(leftMin, rightMin + 1);
                rightMax = Math.min(rightMax, leftMax - 1);
                break;
            case GREATER_EQUALS:
                leftMin = Math.max(leftMin, rightMin);
                rightMax = Math.min(rightMax, leftMax);
                break;
            case EQUALS:
                leftMin = Math.max(leftMin, rightMin);
                leftMax = Math.min(leftMax, rightMax);
                rightMin = leftMin;
                rightMax = leftMax;
                break;
            default:
                // Не равно: отсечь можно только крайнее значение диапазона
                if (left.isConstant() && right.isConstant() && leftMin == rightMin) {
                    return null;
                }
                if (right.isConstant()) {
                    leftMin = leftMin == rightMin ? leftMin + 1 : leftMin;
                    leftMax = leftMax == rightMin ? leftMax - 1 : leftMax;
                } else if (left.isConstant()) {
                    rightMin = rightMin == leftMin ? rightMin + 1 : rightMin;
                    rightMax = rightMax == leftMin ? rightMax - 1 : rightMax;
                }
                break;
        }
        return leftMin > leftMax || rightMin > rightMax ? null : new long[] {leftMin, leftMax, rightMin, rightMax};
    }

    private Branches splitInstanceOf(InstanceOfExpr instanceOf, FlowState state) {
        Expression checked = unwrap(instanceOf.getExpression());
        FlowValue value = evaluate(checked, state);
        if (value.isNull()) {
            reportConstant(instanceOf, false, "'" + snippet(checked) + "' здесь всегда null: " + describe(checked, value));
            return new Branches(null, state);
        }
        FlowState whenTrue = state.copy();
        FlowValue matched = value.withNullness(FlowValue.Nullness.NOT_NULL, "значение прошло проверку instanceof", instanceOf);
        String variable = key(checked, whenTrue);
        if (variable != null) {
            whenTrue.refine(variable, matched);
        }
        instanceOf.getPattern()
                .filter(pattern -> pattern instanceof TypePatternExpr)
                .map(pattern -> (TypePatternExpr) pattern)
                .ifPresent(pattern -> {
                    whenTrue.assign(pattern.getNameAsString(), matched);
                    // При instanceof в условии выхода (if (!(x instanceof T t)) return;) переменная нужна и дальше
                    state.assign(pattern.getNameAsString(), FlowValue.unknown());
                });
        return new Branches(whenTrue, state);
    }

    // Булева переменная в условии
    private Branches splitFlag(String variable, Expression name, FlowState state) {
        FlowValue value = state.get(variable);
        if (value.isConstant(1) || value.isConstant(0)) {
            boolean isTrue = value.isConstant(1);
            // О флаге-переключателе (boolean debug = false) и о накоплении (valid = valid && check()) не сообщаем:
            // так пишут нарочно
            deathReason = "переменная '" + variable + "' на строке " + lineOf(name) + " всегда " + isTrue;
            return isTrue ? new Branches(state, null) : new Branches(null, state);
        }
        if (!value.numeric()) {
            return new Branches(state.copy(), state);
        }
        FlowState whenTrue = state.copy();
        whenTrue.refine(variable, value.withRange(1, 1));
        state.refine(variable, value.withRange(0, 0));
        return new Branches(whenTrue, state);
    }

    // Вызов в условии: isBlank(x), Objects.nonNull(x), "text".equals(x) говорят, равен ли x null
    private Branches splitCall(MethodCallExpr call, FlowState state) {
        FlowValue result = evaluate(call, state);
        if (result.isConstant(1)) {
            return new Branches(state, null);
        }
        if (result.isConstant(0)) {
            return new Branches(null, state);
        }

        String method = call.getNameAsString();
        FlowState whenTrue = state.copy();
        FlowState whenFalse = state;
        Optional<FlowSummary> summary = host.summaryOf(call);
        for (int index = 0; index < call.getArguments().size(); index++) {
            Expression argument = unwrap(call.getArgument(index));
            String variable = key(argument, state);
            if (variable == null) {
                continue;
            }
            Boolean resultForNull = summary.isPresent()
                    ? summary.get().resultForNullParam().get(index)
                    : libraryResultForNull(call, method, index, state);
            if (resultForNull == null) {
                continue;
            }
            // Для null метод вернул бы resultForNull - значит, при другом ответе значение не null
            FlowState notNullState = resultForNull ? whenFalse : whenTrue;
            notNullState.refine(variable, notNullState.get(variable).withNullness(
                    FlowValue.Nullness.NOT_NULL, "значение проверено вызовом " + method, call));
            if (summary.isEmpty() && (IS_NULL.equals(method) || NON_NULL.equals(method))) {
                FlowState nullState = resultForNull ? whenTrue : whenFalse;
                nullState.refine(variable, nullState.get(variable).withNullness(
                        FlowValue.Nullness.NULL, CHECKED_FOR_NULL, call));
            }
        }
        return new Branches(whenTrue, whenFalse);
    }

    /**
     * @return что вернет библиотечный метод-проверка для null в этом аргументе; null - неизвестно
     */
    private Boolean libraryResultForNull(MethodCallExpr call, String method, int index, FlowState state) {
        if (EQUALS_METHODS.contains(method) && call.getScope().isPresent() && call.getArguments().size() == 1) {
            return false;
        }
        if (index > 0 || !isStaticStyle(call, state)) {
            return null;
        }
        if (TRUE_FOR_NULL.contains(method)) {
            return true;
        }
        return FALSE_FOR_NULL.contains(method) ? false : null;
    }

    private void reportConstant(Expression condition, boolean alwaysTrue, String reason) {
        deathReason = "условие '" + snippet(condition) + "' на строке " + lineOf(condition) + " всегда "
                + (alwaysTrue ? "истинно" : "ложно");
        if (quietConditions > 0) {
            return;
        }
        report(FlowAnalysis.Kind.CONSTANT_CONDITION, condition,
                "Условие '" + snippet(condition) + "' всегда " + (alwaysTrue ? "истинно" : "ложно") + ": " + reason
                        + "; либо проверка лишняя, либо в ней ошибка и проверяться должно что-то другое");
    }

    // --------------------------------------------------------------------------------------- вспомогательное

    private void report(FlowAnalysis.Kind kind, Node node, String message) {
        if (reporting && silent == 0) {
            findings.add(new FlowAnalysis.Finding(kind, node, message));
        }
    }

    private Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current instanceof EnclosedExpr enclosed) {
            current = enclosed.getInner();
        }
        return current;
    }

    private static final int SNIPPET_LENGTH = 60;

    private String snippet(Node node) {
        String text = node.toString().replaceAll("\\s+", " ");
        return text.length() <= SNIPPET_LENGTH ? text : text.substring(0, SNIPPET_LENGTH) + "...";
    }

    private int lineOf(Node node) {
        return node.getBegin().map(position -> position.line).orElse(0);
    }

    // Имена переменных, которым внутри узла что-то присваивается
    private Set<String> assignedIn(Node node) {
        Set<String> names = new HashSet<>();
        for (AssignExpr assignment : node.findAll(AssignExpr.class)) {
            Expression target = assignment.getTarget();
            if (target instanceof NameExpr name) {
                names.add(name.getNameAsString());
                names.add(THIS + name.getNameAsString());
            } else if (target instanceof FieldAccessExpr access && access.getScope().isThisExpr()) {
                names.add(THIS + access.getNameAsString());
            }
        }
        // Вызов метода своего объекта мог изменить любое поле
        boolean callsOwnMethod = node.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getScope().map(scope -> scope.isThisExpr() || scope.isSuperExpr()).orElse(true));
        if (callsOwnMethod) {
            names.addAll(fields.keySet());
        }
        for (UnaryExpr unary : node.findAll(UnaryExpr.class)) {
            boolean changes = unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
                    && unary.getOperator() != UnaryExpr.Operator.MINUS
                    && unary.getOperator() != UnaryExpr.Operator.PLUS
                    && unary.getOperator() != UnaryExpr.Operator.BITWISE_COMPLEMENT;
            if (changes && unary.getExpression() instanceof NameExpr name) {
                names.add(name.getNameAsString());
                names.add(THIS + name.getNameAsString());
            }
        }
        return names;
    }

    private Set<String> namesIn(Expression expression) {
        Set<String> names = new HashSet<>();
        expression.findAll(NameExpr.class).forEach(name -> {
            names.add(name.getNameAsString());
            names.add(THIS + name.getNameAsString());
        });
        return names;
    }

    /**
     * @return новая карта значений параметров: параметр с этим номером равен null
     */
    public static Map<Integer, FlowValue> nullParameter(int index, Parameter parameter) {
        Map<Integer, FlowValue> overrides = new HashMap<>();
        overrides.put(index, FlowValue.nullValue("параметр равен null", parameter));
        return overrides;
    }
}
