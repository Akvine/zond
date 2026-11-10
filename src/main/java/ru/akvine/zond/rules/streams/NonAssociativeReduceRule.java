package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamChains;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * reduce() с операцией, которая не выдерживает параллельного выполнения. Параллельный стрим делит элементы на
 * части, сворачивает каждую отдельно и объединяет результаты - расстановка скобок при этом произвольная.
 * Поэтому операция обязана быть ассоциативной, начальное значение - нейтральным, а накапливать в изменяемый
 * объект нельзя вовсе: он один на все потоки.
 */
@Component
public class NonAssociativeReduceRule extends AbstractRule {
    private static final String REDUCE = "reduce";
    private static final Set<String> PARALLEL = Set.of("parallel", "parallelStream");
    private static final String SEQUENTIAL = "sequential";
    private static final int WITH_IDENTITY = 2;
    private static final Set<String> STREAM_STEPS = Set.of(
            "stream", "parallelStream", "parallel", "sequential", "boxed", "mapToInt", "mapToLong", "mapToDouble",
            "mapToObj", "chars", "lines");
    private static final Set<String> STREAM_TYPES = Set.of("Stream", "IntStream", "LongStream", "DoubleStream", "Arrays");

    // a - b, a / b, a % b и их записи методами: от расстановки скобок результат зависит
    private static final Set<BinaryExpr.Operator> NON_ASSOCIATIVE_OPERATORS = Set.of(
            BinaryExpr.Operator.MINUS, BinaryExpr.Operator.DIVIDE, BinaryExpr.Operator.REMAINDER);
    private static final Set<String> NON_ASSOCIATIVE_METHODS = Set.of(
            "subtract", "divide", "remainder", "mod", "minus", "subtractExact", "floorDiv", "floorMod", "pow");
    private static final Set<String> SUM_METHODS = Set.of("sum", "add", "addExact", "plus");
    private static final Set<String> PRODUCT_METHODS = Set.of("multiply", "multiplyExact");
    private static final Set<String> ZEROS = Set.of(
            "0", "0L", "0l", "0.0", "0d", "0D", "0f", "0F", "0.0d", "0.0f", "\"\"", "BigDecimal.ZERO", "BigInteger.ZERO",
            "Duration.ZERO", "Money.ZERO");
    private static final Set<String> ONES = Set.of(
            "1", "1L", "1l", "1.0", "1d", "1D", "1f", "1F", "1.0d", "1.0f", "BigDecimal.ONE", "BigInteger.ONE");
    // Объекты, в которые накапливают, изменяя их самих
    private static final Set<String> MUTABLE_CONTAINERS = Set.of(
            "StringBuilder", "StringBuffer", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet",
            "HashMap", "LinkedHashMap", "TreeMap", "StringJoiner");

    @Override
    public String code() {
        return RuleCodes.NON_ASSOCIATIVE_REDUCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет reduce() с неассоциативной операцией (вычитание, деление), не нейтральным"
                + " начальным значением или изменяемым накопителем - в параллельном стриме результат неверен";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr reduce : sourceFile.unit().findAll(MethodCallExpr.class)) {
            int arguments = reduce.getArguments().size();
            if (!REDUCE.equals(reduce.getNameAsString()) || arguments == 0 || arguments > 3 || !isOnStream(reduce)) {
                continue;
            }
            boolean parallel = isParallel(reduce);
            // Цепочка может занимать несколько строк: находка ставится на строку самого reduce
            describe(reduce, parallel).ifPresent(problem -> violations.add(violation(
                    sourceFile, reduce.getName(), problem).withLevel(parallel ? ErrorLevel.MAJOR : ErrorLevel.MINOR)));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    private Optional<String> describe(MethodCallExpr reduce, boolean parallel) {
        int arguments = reduce.getArguments().size();
        // reduce(операция), reduce(начальное значение, операция), reduce(начальное значение, операция, объединение)
        Expression accumulator = Nodes.unwrap(reduce.getArgument(arguments == 1 ? 0 : 1));
        Optional<Expression> identity = arguments >= WITH_IDENTITY
                ? Optional.of(Nodes.unwrap(reduce.getArgument(0)))
                : Optional.empty();
        Optional<String> operation = nonAssociative(accumulator);
        if (operation.isPresent()) {
            return Optional.of("reduce() с неассоциативной операцией (" + operation.get() + ")"
                    + (parallel
                    ? " в параллельном стриме: элементы делятся на части, каждая сворачивается отдельно, а"
                    + " результаты объединяются в произвольной расстановке скобок - (a - b) - c не равно"
                    + " a - (b - c), поэтому итог зависит от разбиения и меняется от запуска к запуску"
                    : ": пока стрим последовательный, результат один и тот же, но стоит добавить parallel() -"
                    + " и он начнет зависеть от того, как стрим разобьет элементы на части")
                    + "; сверните элементы ассоциативной операцией (сумма, произведение), а вычитание или деление"
                    + " выполните один раз над итогом: total - stream.reduce(0, Integer::sum)");
        }

        boolean mutable = identity.filter(Expression::isObjectCreationExpr)
                .filter(value -> MUTABLE_CONTAINERS.contains(value.asObjectCreationExpr().getType().getNameAsString()))
                .isPresent();
        if (mutable) {
            return Optional.of("reduce() накапливает в изменяемый объект '" + Nodes.text(identity.get()) + "'"
                    + (parallel
                    ? " в параллельном стриме: начальное значение одно на все потоки, они одновременно меняют"
                    + " один и тот же объект, и результат искажается"
                    : ": reduce рассчитан на неизменяемые значения, и с parallel() один и тот же объект начнут"
                    + " одновременно менять несколько потоков")
                    + "; для накопления в коллекцию или строку используйте collect(): Collectors.toList(),"
                    + " Collectors.joining()");
        }

        // В последовательном стриме начальное значение участвует один раз - это просто слагаемое
        if (!parallel || identity.isEmpty()) {
            return Optional.empty();
        }
        String start = Nodes.text(identity.get());
        boolean constant = identity.get().isLiteralExpr() || ZEROS.contains(start) || ONES.contains(start);
        boolean sum = isOperation(accumulator, BinaryExpr.Operator.PLUS, SUM_METHODS);
        boolean wrongStart = constant && (sum && !ZEROS.contains(start)
                || isOperation(accumulator, BinaryExpr.Operator.MULTIPLY, PRODUCT_METHODS) && !ONES.contains(start));
        return wrongStart
                ? Optional.of("reduce(" + start + ", ...) в параллельном стриме: начальное значение должно быть"
                + " нейтральным (0 для суммы, 1 для произведения, пустая строка для склейки) - стрим подставляет"
                + " его в каждую часть, и '" + start + "' войдет в результат столько раз, на сколько частей"
                + " разобьются элементы; передайте нейтральное значение, а '" + start + "' добавьте к итогу отдельно")
                : Optional.empty();
    }

    /**
     * @return название операции, если результат свертки зависит от расстановки скобок
     */
    private Optional<String> nonAssociative(Expression accumulator) {
        // BigDecimal::subtract, Math::floorDiv
        if (accumulator.isMethodReferenceExpr()) {
            String method = accumulator.asMethodReferenceExpr().getIdentifier();
            return NON_ASSOCIATIVE_METHODS.contains(method) ? Optional.of(Nodes.text(accumulator)) : Optional.empty();
        }
        Optional<Expression> body = bodyOf(accumulator);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        // (a, b) -> a - b, (a, b) -> (a + b) / 2
        if (body.get().isBinaryExpr() && NON_ASSOCIATIVE_OPERATORS.contains(body.get().asBinaryExpr().getOperator())) {
            return Optional.of("'" + Nodes.text(body.get()) + "'");
        }
        // (a, b) -> a.subtract(b), (a, b) -> Math.floorDiv(a, b)
        return body.filter(Expression::isMethodCallExpr)
                .filter(call -> NON_ASSOCIATIVE_METHODS.contains(call.asMethodCallExpr().getNameAsString()))
                .map(call -> "'" + Nodes.text(call) + "'");
    }

    // Integer::sum, (a, b) -> a + b, (a, b) -> a.add(b)
    private boolean isOperation(Expression accumulator, BinaryExpr.Operator operator, Set<String> methods) {
        if (accumulator.isMethodReferenceExpr()) {
            return methods.contains(accumulator.asMethodReferenceExpr().getIdentifier());
        }
        Optional<Expression> body = bodyOf(accumulator);
        if (body.isEmpty()) {
            return false;
        }
        return body.get().isBinaryExpr() && body.get().asBinaryExpr().getOperator() == operator
                || body.get().isMethodCallExpr() && methods.contains(body.get().asMethodCallExpr().getNameAsString());
    }

    /**
     * @return выражение, которое возвращает лямбда с двумя параметрами: тело-выражение либо единственный return
     */
    private Optional<Expression> bodyOf(Expression accumulator) {
        if (!accumulator.isLambdaExpr() || accumulator.asLambdaExpr().getParameters().size() != WITH_IDENTITY) {
            return Optional.empty();
        }
        LambdaExpr lambda = accumulator.asLambdaExpr();
        if (lambda.getExpressionBody().isPresent()) {
            return lambda.getExpressionBody().map(Nodes::unwrap);
        }
        List<Statement> statements = lambda.getBody().isBlockStmt() ? lambda.getBody().asBlockStmt().getStatements() : List.of();
        return statements.size() == 1 && statements.get(0).isReturnStmt()
                ? statements.get(0).asReturnStmt().getExpression().map(Nodes::unwrap)
                : Optional.empty();
    }

    // Свертка есть и у Flux из Reactor, и у своих классов: там она выполняется по порядку и правилу не интересна
    private boolean isOnStream(MethodCallExpr reduce) {
        List<MethodCallExpr> before = StreamChains.callsBefore(reduce);
        boolean streamStep = before.stream().anyMatch(step -> STREAM_STEPS.contains(step.getNameAsString())
                || step.getScope().filter(scope -> STREAM_TYPES.contains(scope.toString())).isPresent());
        Optional<Expression> root = before.isEmpty() ? reduce.getScope() : before.get(before.size() - 1).getScope();
        return streamStep || root.flatMap(LocalTypes::typeOf).filter(STREAM_TYPES::contains).isPresent();
    }

    // parallel() или parallelStream() выше по цепочке, после которых стрим не вернули в последовательный режим
    private boolean isParallel(MethodCallExpr reduce) {
        for (MethodCallExpr step : StreamChains.callsBefore(reduce)) {
            if (SEQUENTIAL.equals(step.getNameAsString())) {
                return false;
            }
            if (PARALLEL.contains(step.getNameAsString()) && step.getArguments().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
