package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.DataValues;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code values.stream().map(value -> { long result = 1; for (long i = 2; i <= value; i++) result *= i; ... })}:
 * цикл выполняется столько раз, каково значение числа, а не сколько элементов в коллекции. Пока числа маленькие,
 * разницы нет. На числе в несколько миллиардов поток занят минутами, на числе из диапазона long - годами, и пул
 * потоков, в котором выполняется такая работа, перестает обслуживать остальные запросы. Так вычисляют факториал,
 * степень, сумму ряда, простоту числа. Значение нужно проверить на верхнюю границу до цикла.
 */
@Component
public class ValueBoundedLoopRule extends AbstractRule {
    private static final Set<BinaryExpr.Operator> BOUND_CHECKS = Set.of(BinaryExpr.Operator.LESS,
            BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS,
            BinaryExpr.Operator.NOT_EQUALS);
    private static final Set<String> RANGE_METHODS = Set.of("range", "rangeClosed");
    private static final Set<String> RANGE_TYPES = Set.of("IntStream", "LongStream");
    private static final String LONG_STREAM = "LongStream";
    private static final Set<String> LONG_TYPES = Set.of("long", "Long");
    private static final Set<String> COLLECTING_METHODS = Set.of("add", "append", "put", "addAll", "offer", "push");
    // in.readInt(), iterator.next(), queue.poll(): каждый шаг забирает очередную часть входных данных, и больше
    // шагов, чем данных, цикл не сделает
    private static final Pattern CONSUMING_METHOD =
            Pattern.compile("(read|next|skip|poll|take|pop|remove|consume)\\w*");
    // random.nextInt() ничего не забирает
    private static final List<String> RANDOM_NAMES = List.of("random", "rnd", "rand", "generator", "faker");
    private static final Set<String> RANDOM_TYPES = Set.of("Random", "SecureRandom", "ThreadLocalRandom",
            "RandomGenerator", "SplittableRandom");
    // list.get(i), text.charAt(i): счетчик - это позиция в коллекции или строке, и число шагов задает ее размер
    private static final Set<String> INDEX_METHODS = Set.of("get", "charAt", "set", "getRow", "getCell", "codePointAt",
            "substring", "elementAt", "item", "getParameterTypes");

    @Override
    public String code() {
        return RuleCodes.VALUE_BOUNDED_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет циклы, число итераций которых равно значению числа из данных - элемента"
                + " коллекции, разобранной строки, числа из потока - и ничем сверху не ограничено";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        if (TestPaths.isTest(sourceFile.path())) {
            return violations;
        }

        for (ForStmt loop : sourceFile.unit().findAll(ForStmt.class)) {
            loop.getCompare()
                    .flatMap(compare -> findBound(compare, loop.getUpdate()))
                    .flatMap(bound -> check(sourceFile, loop, loop.getBody(), bound))
                    .ifPresent(violations::add);
        }
        for (WhileStmt loop : sourceFile.unit().findAll(WhileStmt.class)) {
            findBound(loop.getCondition(), List.of(loop.getBody()))
                    .flatMap(bound -> check(sourceFile, loop, loop.getBody(), bound))
                    .ifPresent(violations::add);
        }
        for (DoStmt loop : sourceFile.unit().findAll(DoStmt.class)) {
            findBound(loop.getCondition(), List.of(loop.getBody()))
                    .flatMap(bound -> check(sourceFile, loop, loop.getBody(), bound))
                    .ifPresent(violations::add);
        }
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            checkRange(sourceFile, call).ifPresent(violations::add);
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    /**
     * Граница цикла и его счетчик
     *
     * @param countdown true, если счетчиком служит само значение: while (n > 0) { ...; n--; }
     */
    private record Bound(Expression value, String counter, boolean countdown) {
    }

    // i < n, n >= i: счетчик - та сторона сравнения, которая меняется на каждом шаге
    private Optional<Bound> findBound(Expression condition, List<? extends Node> updates) {
        List<String> counters = DataValues.steppedNames(updates);
        Expression compare = Nodes.unwrap(condition);
        if (!compare.isBinaryExpr() || !BOUND_CHECKS.contains(compare.asBinaryExpr().getOperator())) {
            return Optional.empty();
        }
        Expression left = DataValues.strip(compare.asBinaryExpr().getLeft());
        Expression right = DataValues.strip(compare.asBinaryExpr().getRight());
        boolean leftCounts = left.isNameExpr() && counters.contains(left.asNameExpr().getNameAsString());
        boolean rightCounts = right.isNameExpr() && counters.contains(right.asNameExpr().getNameAsString());
        if (leftCounts == rightCounts) {
            return Optional.empty();
        }

        Expression counter = leftCounts ? left : right;
        Expression other = leftCounts ? compare.asBinaryExpr().getRight() : compare.asBinaryExpr().getLeft();
        String name = counter.asNameExpr().getNameAsString();
        if (!DataValues.isConstant(other)) {
            return Optional.of(new Bound(other, name, false));
        }
        // while (n > 0) { ...; n--; }: шагов столько, каким было значение n перед циклом. Счетчик, который растет
        // до константы, к данным отношения не имеет
        return isDecreased(updates, name) ? Optional.of(new Bound(counter, name, true)) : Optional.empty();
    }

    private boolean isDecreased(List<? extends Node> updates, String name) {
        for (Node update : updates) {
            boolean decremented = update.findAll(UnaryExpr.class).stream()
                    .anyMatch(step -> (step.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT
                            || step.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT)
                            && Nodes.unwrap(step.getExpression()).toString().equals(name));
            boolean subtracted = update.findAll(AssignExpr.class).stream()
                    .anyMatch(step -> step.getOperator() == AssignExpr.Operator.MINUS
                            && Nodes.unwrap(step.getTarget()).toString().equals(name));
            if (decremented || subtracted) {
                return true;
            }
        }
        return false;
    }

    private Optional<Violation> check(SourceFile sourceFile, Statement loop, Statement body, Bound bound) {
        Optional<DataValues.DataValue> value = DataValues.find(bound.value());
        if (value.isEmpty() || consumesInput(body, bound.counter())) {
            return Optional.empty();
        }
        // До значения параметра long считают long-счетчиком: int-счетчик переполнится раньше и цикл не закончится
        // по другой причине, о которой сообщают другие правила
        if (value.get().origin() == DataValues.Origin.LONG_PARAMETER && !isLong(body, bound.counter())) {
            return Optional.empty();
        }
        if (DataValues.isBoundedAbove(loop, value.get(), loop)) {
            return Optional.empty();
        }
        return Optional.of(violation(sourceFile, loop, value.get(),
                "Цикл выполняется столько раз, каково значение числа: " + value.get().source(), collects(body)));
    }

    // IntStream.range(0, n), LongStream.rangeClosed(1, n): тот же цикл, записанный стримом
    private Optional<Violation> checkRange(SourceFile sourceFile, MethodCallExpr call) {
        if (!RANGE_METHODS.contains(call.getNameAsString()) || call.getArguments().size() != 2
                || call.getScope().filter(scope -> RANGE_TYPES.contains(scope.toString())).isEmpty()) {
            return Optional.empty();
        }
        Optional<DataValues.DataValue> value = DataValues.find(call.getArgument(1));
        if (value.isEmpty()) {
            return Optional.empty();
        }
        boolean longRange = MethodCalls.isCallOn(call, LONG_STREAM, call.getNameAsString());
        if (value.get().origin() == DataValues.Origin.LONG_PARAMETER && !longRange) {
            return Optional.empty();
        }
        if (DataValues.isBoundedAbove(call, value.get(), null)) {
            return Optional.empty();
        }
        return Optional.of(violation(sourceFile, call, value.get(),
                "В '" + call + "' столько чисел, каково значение границы: " + value.get().source(), false));
    }

    private Violation violation(SourceFile sourceFile, Node node, DataValues.DataValue value, String start,
                                boolean collects) {
        String memory = collects
                ? ", а все, что цикл складывает в коллекцию или строку, займет память (OutOfMemoryError)"
                : "";
        Violation violation = violation(sourceFile, node, start + ", и сверху его никто не ограничил. На большом"
                + " числе поток будет занят минутами и часами, а пул потоков перестанет обслуживать остальные"
                + " задачи" + memory + "; проверьте верхнюю границу значения до цикла");
        // Параметр мог проверить вызывающий код
        return value.origin() == DataValues.Origin.LONG_PARAMETER
                ? violation.withConfidence(Confidence.SUSPICION)
                : violation;
    }

    // result.add(...), builder.append(...): с каждым шагом в памяти остается еще один элемент
    private boolean collects(Statement body) {
        return body.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> COLLECTING_METHODS.contains(call.getNameAsString()) && call.getScope().isPresent());
    }

    /**
     * @return true, если число шагов цикла ограничено самими данными: счетчик или то, что вычислено из него,
     * служит позицией в массиве, списке или строке (values[index++], text.charAt(i)), либо каждый шаг читает
     * очередную часть входа. Выйти за пределы данных такой цикл не может - он завершится исключением
     */
    private boolean consumesInput(Statement body, String counter) {
        Set<String> moving = movingNames(body, counter);
        boolean indexed = body.findAll(ArrayAccessExpr.class).stream()
                .anyMatch(access -> mentionsAny(access.getIndex(), moving));
        return indexed || body.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> isIndexed(call, moving) || readsNext(call));
    }

    private boolean isIndexed(MethodCallExpr call, Set<String> moving) {
        return INDEX_METHODS.contains(call.getNameAsString()) && !call.getArguments().isEmpty()
                && mentionsAny(call.getArgument(0), moving);
    }

    private boolean readsNext(MethodCallExpr call) {
        if (!CONSUMING_METHOD.matcher(call.getNameAsString()).matches()) {
            return false;
        }
        // readValue(sentence, index, 2): свой метод разбора
        if (call.getScope().isEmpty()) {
            return true;
        }
        Expression source = Nodes.unwrap(call.getScope().get());
        String name = MethodCalls.receiverName(source).toLowerCase();
        return RANDOM_NAMES.stream().noneMatch(name::contains)
                && LocalTypes.typeOf(source).filter(RANDOM_TYPES::contains).isEmpty();
    }

    // Счетчик и все, что меняется вместе с ним: index++, offset = 6 + i * 10
    private Set<String> movingNames(Statement body, String counter) {
        Set<String> moving = new HashSet<>();
        moving.add(counter);
        moving.addAll(DataValues.steppedNames(List.of(body)));
        for (VariableDeclarator variable : body.findAll(VariableDeclarator.class)) {
            if (variable.getInitializer().filter(initializer -> mentionsAny(initializer, moving)).isPresent()) {
                moving.add(variable.getNameAsString());
            }
        }
        return moving;
    }

    private boolean mentionsAny(Node node, Set<String> names) {
        return names.stream().anyMatch(name -> DataValues.mentions(node, name));
    }

    private boolean isLong(Node usage, String counter) {
        return LocalTypes.findDeclaration(usage, counter)
                .flatMap(declaration -> declaration instanceof VariableDeclarator variable
                        ? Optional.of(variable.getTypeAsString())
                        : declaration instanceof Parameter parameter
                                ? Optional.of(parameter.getTypeAsString())
                                : Optional.<String>empty())
                .filter(LONG_TYPES::contains)
                .isPresent();
    }
}
