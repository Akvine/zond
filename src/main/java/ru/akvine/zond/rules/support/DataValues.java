package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Числа, которые приходят из данных, а не из кода: элемент коллекции или стрима, результат разбора строки, число,
 * прочитанное из потока. Такое число может оказаться любым. Если от него зависит число итераций или размер
 * выделяемой памяти, а сверху его никто не ограничил, одно большое значение занимает поток на часы или
 * исчерпывает память
 */
@UtilityClass
public class DataValues {
    private static final Set<String> PARSE_METHODS = Set.of("parseInt", "parseLong", "parseShort",
            "parseUnsignedInt", "parseUnsignedLong", "decode", "toInt", "toLong", "createInteger", "createLong");
    private static final Set<String> PARSING_TYPES = Set.of("Integer", "Long", "Short", "NumberUtils");
    private static final String VALUE_OF = "valueOf";
    private static final Set<String> BOXED_TYPES = Set.of("Integer", "Long", "Short");
    private static final String STRING = "String";
    // Без аргументов: buffer.getInt() читает очередное число, а resultSet.getInt("count") - колонку своей же базы.
    // readShort и readUnsignedByte сюда не входят: больше 65535 такое число не бывает
    private static final Set<String> READ_METHODS = Set.of("readInt", "readLong", "readUnsignedInt", "readVarInt",
            "readVarLong", "readIntLE", "readLongLE", "getInt", "getLong");
    private static final Set<String> UNBOXING_METHODS = Set.of("intValue", "longValue", "shortValue");
    private static final Set<String> SAME_VALUE_METHODS = Set.of("abs", "toIntExact", "max");
    private static final String MATH = "Math";
    private static final String MIN = "min";
    // Операции, лямбда которых получает элемент коллекции или стрима
    private static final Set<String> ELEMENT_METHODS = Set.of("map", "mapToObj", "mapToInt", "mapToLong",
            "mapToDouble", "flatMap", "forEach", "forEachOrdered", "filter", "peek", "anyMatch", "allMatch",
            "noneMatch", "takeWhile", "dropWhile", "removeIf", "replaceAll");
    // IntStream.range(0, 10).forEach(i -> ...): элемент такого стрима - счетчик, а не данные
    private static final Set<String> COUNTER_SOURCES = Set.of("range", "rangeClosed", "iterate", "generate");
    private static final Set<String> LONG_TYPES = Set.of("long", "Long");
    private static final Set<String> BOUND_ANNOTATIONS = Set.of("Max", "Range", "DecimalMax", "Size");
    // checkLimit(count), validateSize(count): проверка вынесена в метод
    private static final Pattern CHECK_METHOD =
            Pattern.compile("(check|validate|verify|ensure|require|assert|limit|clamp|restrict)\\w*");
    // checkNonNegative(count), requireNonNull(count) проверяют другое: сверху значение остается любым
    private static final Pattern OTHER_CHECK =
            Pattern.compile("(?i)\\w*(null|negative|positive|zero|empty|blank|state)\\w*");
    // count == expected тоже граница: значение, которое с ожидаемым не совпало, дальше не идет
    private static final Set<BinaryExpr.Operator> COMPARISONS = Set.of(BinaryExpr.Operator.LESS,
            BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS,
            BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS);
    private static final Set<BinaryExpr.Operator> ARITHMETIC = Set.of(BinaryExpr.Operator.PLUS,
            BinaryExpr.Operator.MINUS, BinaryExpr.Operator.MULTIPLY, BinaryExpr.Operator.DIVIDE);
    private static final Set<BinaryExpr.Operator> COMMUTATIVE =
            Set.of(BinaryExpr.Operator.PLUS, BinaryExpr.Operator.MULTIPLY);
    // Сравнение с таким числом - это проверка на ноль и знак или условие выхода из рекурсии, а не верхняя граница
    private static final long MAX_NOT_A_LIMIT = 2;
    private static final int MAX_ALIASES = 3;
    // На сколько вызовов вглубь искать проверку границы: check(count) -> checkRange(count, LIMIT)
    private static final int MAX_CHECK_DEPTH = 2;

    public enum Origin {
        /** Элемент коллекции, массива или стрима */
        ELEMENT,
        /** Результат разбора строки: Integer.parseInt(text) */
        PARSED,
        /** Число, прочитанное из потока или буфера: in.readInt() */
        READ,
        /** Параметр метода типа long: вызывающий код может передать что угодно */
        LONG_PARAMETER
    }

    /**
     * @param text   как значение записано в коде
     * @param names  переменные, через которые значение дошло до места использования: проверка любой из них
     *               ограничивает значение
     */
    public record DataValue(String text, Origin origin, List<String> names) {
        /**
         * @return откуда взялось значение - для текста находки
         */
        public String source() {
            return switch (origin) {
                case ELEMENT -> "'" + text + "' - элемент данных";
                case PARSED -> "'" + text + "' получено разбором строки";
                case READ -> "'" + text + "' прочитано из потока";
                case LONG_PARAMETER -> "'" + text + "' - параметр типа long";
            };
        }
    }

    /**
     * @return число из данных, которым задано выражение, если оно таково. Прибавление константы, приведение типа,
     * Math.max(0, x) значение сверху не ограничивают и сквозь них выражение просматривается
     */
    public Optional<DataValue> find(Expression expression) {
        return find(expression, new ArrayList<>());
    }

    private Optional<DataValue> find(Expression expression, List<String> names) {
        Expression value = strip(expression);
        if (value.isMethodCallExpr()) {
            MethodCallExpr call = value.asMethodCallExpr();
            if (isParsing(call)) {
                return Optional.of(new DataValue(text(expression, names), Origin.PARSED, names));
            }
            if (isReading(call)) {
                return Optional.of(new DataValue(text(expression, names), Origin.READ, names));
            }
            return Optional.empty();
        }
        if (value.isConditionalExpr()) {
            // count = arguments.length != 0 ? Integer.parseInt(arguments[0]) : 1
            Optional<DataValue> then = find(value.asConditionalExpr().getThenExpr(), names);
            return then.isPresent() ? then : find(value.asConditionalExpr().getElseExpr(), names);
        }
        if (!value.isNameExpr() || names.size() >= MAX_ALIASES) {
            return Optional.empty();
        }

        String name = value.asNameExpr().getNameAsString();
        Optional<Node> declaration = LocalTypes.findDeclaration(value, name);
        if (declaration.isEmpty() || !LocalTypes.isLocal(value, name)) {
            return Optional.empty();
        }
        List<String> seen = new ArrayList<>(names);
        seen.add(name);

        Node declared = declaration.get();
        if (declared instanceof Parameter parameter) {
            Node owner = parameter.getParentNode().orElse(null);
            if (owner instanceof LambdaExpr lambda) {
                return isElementLambda(lambda)
                        ? Optional.of(new DataValue(seen.get(0), Origin.ELEMENT, seen))
                        : Optional.empty();
            }
            if (owner instanceof MethodDeclaration && LONG_TYPES.contains(parameter.getTypeAsString())
                    && !Annotations.hasAny(parameter, BOUND_ANNOTATIONS)) {
                return Optional.of(new DataValue(seen.get(0), Origin.LONG_PARAMETER, seen));
            }
            return Optional.empty();
        }
        if (declared instanceof VariableDeclarator variable) {
            if (isForEachVariable(variable)) {
                return Optional.of(new DataValue(seen.get(0), Origin.ELEMENT, seen));
            }
            Optional<DataValue> initial = variable.getInitializer().flatMap(initializer -> find(initializer, seen));
            return initial.isPresent() ? initial : findAssigned(value, name, seen);
        }
        return Optional.empty();
    }

    // int count; if (given) { count = Integer.parseInt(text); } else { count = 1; }
    private Optional<DataValue> findAssigned(Expression usage, String name, List<String> seen) {
        return Nodes.enclosingCallable(usage).stream()
                .flatMap(callable -> callable.findAll(AssignExpr.class).stream())
                .filter(assignment -> assignment.getOperator() == AssignExpr.Operator.ASSIGN
                        && Nodes.unwrap(assignment.getTarget()).toString().equals(name))
                .map(assignment -> find(assignment.getValue(), seen))
                .flatMap(Optional::stream)
                .findFirst();
    }

    // В тексте находки называется переменная, которую видно в месте использования, либо сам вызов
    private String text(Expression expression, List<String> names) {
        return names.isEmpty() ? strip(expression).toString() : names.get(0);
    }

    /**
     * (int) x, x + 1, 2 * x, Math.max(0, x), Math.abs(x), x.intValue() -> x. Math.min(x, LIMIT) остается как есть:
     * это и есть ограничение сверху
     */
    public Expression strip(Expression expression) {
        Expression current = Nodes.unwrap(expression);
        while (true) {
            Expression next = current;
            if (current.isCastExpr()) {
                next = current.asCastExpr().getExpression();
            } else if (current.isBinaryExpr() && ARITHMETIC.contains(current.asBinaryExpr().getOperator())) {
                BinaryExpr binary = current.asBinaryExpr();
                // WIDTH - x и TOTAL / x с ростом x убывают: сверху их ограничивает константа
                if (isConstant(binary.getRight())) {
                    next = binary.getLeft();
                } else if (isConstant(binary.getLeft()) && COMMUTATIVE.contains(binary.getOperator())) {
                    next = binary.getRight();
                }
            } else if (current.isMethodCallExpr()) {
                MethodCallExpr call = current.asMethodCallExpr();
                if (UNBOXING_METHODS.contains(call.getNameAsString()) && call.getArguments().isEmpty()
                        && call.getScope().isPresent()) {
                    next = call.getScope().get();
                } else if (MethodCalls.isCallOn(call, MATH, call.getNameAsString())
                        && SAME_VALUE_METHODS.contains(call.getNameAsString())) {
                    next = call.getArguments().stream()
                            .filter(argument -> !isConstant(argument))
                            .findFirst()
                            .orElse(current);
                }
            }
            next = Nodes.unwrap(next);
            if (next == current) {
                return current;
            }
            current = next;
        }
    }

    /**
     * @return true, если значение хоть где-то в методе сравнивают с верхней границей, обрезают через Math.min,
     * передают в проверяющий метод или заменяют результатом вычисления. Сравнения в заголовках циклов не считаются:
     * там значение само служит границей. Узлы внутри ignored не просматриваются
     */
    public boolean isBoundedAbove(Node usage, DataValue value, Node ignored) {
        Node root = Nodes.enclosingCallable(usage).orElse(null);
        if (root == null || value.names().isEmpty()) {
            return false;
        }
        if (isBoundedIn(root, Set.copyOf(value.names()), ignored, MAX_CHECK_DEPTH)) {
            return true;
        }
        if (value.origin() != Origin.LONG_PARAMETER || !(root instanceof MethodDeclaration method)) {
            return false;
        }
        // Значение пришло параметром: границу мог проверить тот, кто вызывает метод
        String parameter = value.names().get(value.names().size() - 1);
        for (int position = 0; position < method.getParameters().size(); position++) {
            if (method.getParameter(position).getNameAsString().equals(parameter)) {
                return isBoundedByCallers(method, position);
            }
        }
        return false;
    }

    /**
     * Параметр закрытого метода получает только то, что передали из этого же файла. Если все вызовы передают
     * константу или значение, которое вызывающий код уже ограничил, проверять границу в самом методе незачем
     *
     * @return true, если метод закрытый, вызовы найдены и каждый передает в параметр ограниченное значение
     */
    public boolean isBoundedByCallers(MethodDeclaration method, int position) {
        Optional<CompilationUnit> unit = method.findCompilationUnit();
        if (!method.isPrivate() || unit.isEmpty()) {
            return false;
        }
        List<MethodCallExpr> calls = unit.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> isCallOf(call, method) && !Nodes.isInside(call, method))
                .toList();
        return !calls.isEmpty() && calls.stream().allMatch(call -> isBoundedArgument(call, call.getArgument(position)));
    }

    private boolean isBoundedArgument(MethodCallExpr call, Expression argument) {
        // strip(...) оставляет Math.min(count, LIMIT) как есть: это и есть ограничение
        Expression value = strip(argument);
        boolean clamped = value.isMethodCallExpr() && MethodCalls.isCallOn(value.asMethodCallExpr(), MATH, MIN);
        if (isConstant(value) || clamped) {
            return true;
        }
        Node root = Nodes.enclosingCallable(call).orElse(null);
        return value.isNameExpr() && root != null
                && isBoundedIn(root, Set.of(value.asNameExpr().getNameAsString()), null, MAX_CHECK_DEPTH);
    }

    private boolean isCallOf(MethodCallExpr call, MethodDeclaration method) {
        return call.getNameAsString().equals(method.getNameAsString())
                && call.getArguments().size() == method.getParameters().size()
                && call.getScope().filter(scope -> !scope.isThisExpr()).isEmpty();
    }

    private boolean isBoundedIn(Node root, Set<String> names, Node ignored, int depth) {
        for (BinaryExpr comparison : root.findAll(BinaryExpr.class)) {
            if (!COMPARISONS.contains(comparison.getOperator()) || isSkipped(comparison, ignored)
                    || isLoopCondition(comparison)) {
                continue;
            }
            boolean left = isOneOf(comparison.getLeft(), names);
            boolean right = isOneOf(comparison.getRight(), names);
            if (left && isLimit(comparison.getRight()) || right && isLimit(comparison.getLeft())) {
                return true;
            }
        }
        for (MethodCallExpr call : root.findAll(MethodCallExpr.class)) {
            if (isSkipped(call, ignored)) {
                continue;
            }
            String name = call.getNameAsString();
            boolean limits = MethodCalls.isCallOn(call, MATH, MIN)
                    || CHECK_METHOD.matcher(name).matches() && !OTHER_CHECK.matcher(name).matches();
            for (int position = 0; position < call.getArguments().size(); position++) {
                if (isOneOf(call.getArgument(position), names)
                        && (limits || depth > 0 && isBoundedByCallee(call, position, depth - 1))) {
                    return true;
                }
            }
        }
        for (AssignExpr assignment : root.findAll(AssignExpr.class)) {
            if (!isSkipped(assignment, ignored) && isOneOf(assignment.getTarget(), names) && limits(assignment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * guard(count), где {@code void guard(int value) { if (value > LIMIT) throw ...; }} объявлен в этом же файле:
     * границу проверяет вызванный метод, как бы он ни назывался
     */
    private boolean isBoundedByCallee(MethodCallExpr call, int position, int depth) {
        Optional<CompilationUnit> unit = call.findCompilationUnit();
        if (unit.isEmpty()) {
            return false;
        }
        Node caller = Nodes.enclosingCallable(call).orElse(null);
        return unit.get().findAll(MethodDeclaration.class).stream()
                .filter(method -> method != caller && method.getBody().isPresent()
                        && method.getNameAsString().equals(call.getNameAsString())
                        && method.getParameters().size() == call.getArguments().size())
                .anyMatch(method -> isBoundedIn(method,
                        Set.of(method.getParameter(position).getNameAsString()), null, depth));
    }

    // count = clamp(count), count %= SIZE ограничивают значение. count = Integer.parseInt(text) и count = 1 его
    // задают, count += 1 лишь сдвигает
    private boolean limits(AssignExpr assignment) {
        return switch (assignment.getOperator()) {
            case ASSIGN -> !isConstant(assignment.getValue()) && find(assignment.getValue()).isEmpty();
            case PLUS, MINUS, MULTIPLY -> false;
            default -> true;
        };
    }

    /**
     * @return имена переменных, которые меняются в выражениях на единицу или константу: i++, i--, i += 2
     */
    public List<String> steppedNames(List<? extends Node> updates) {
        List<String> names = new ArrayList<>();
        for (Node update : updates) {
            for (UnaryExpr step : update.findAll(UnaryExpr.class)) {
                if (isStep(step.getOperator()) && Nodes.unwrap(step.getExpression()).isNameExpr()) {
                    names.add(Nodes.unwrap(step.getExpression()).asNameExpr().getNameAsString());
                }
            }
            for (AssignExpr step : update.findAll(AssignExpr.class)) {
                boolean stepped = step.getOperator() == AssignExpr.Operator.PLUS
                        || step.getOperator() == AssignExpr.Operator.MINUS;
                if (stepped && isConstant(step.getValue()) && Nodes.unwrap(step.getTarget()).isNameExpr()) {
                    names.add(Nodes.unwrap(step.getTarget()).asNameExpr().getNameAsString());
                }
            }
        }
        return names;
    }

    private boolean isStep(UnaryExpr.Operator operator) {
        return operator == UnaryExpr.Operator.POSTFIX_INCREMENT || operator == UnaryExpr.Operator.PREFIX_INCREMENT
                || operator == UnaryExpr.Operator.POSTFIX_DECREMENT || operator == UnaryExpr.Operator.PREFIX_DECREMENT;
    }

    private boolean isParsing(MethodCallExpr call) {
        if (call.getArguments().isEmpty() || call.getScope().isEmpty()) {
            return false;
        }
        String type = Nodes.unwrap(call.getScope().get()).toString();
        String method = call.getNameAsString();
        if (PARSE_METHODS.contains(method) && PARSING_TYPES.contains(type)) {
            return !call.getArgument(0).isLiteralExpr();
        }
        // Integer.valueOf(text) разбирает строку, Integer.valueOf(number) только упаковывает число
        return VALUE_OF.equals(method) && BOXED_TYPES.contains(type)
                && LocalTypes.typeOf(call.getArgument(0)).filter(STRING::equals).isPresent();
    }

    private boolean isReading(MethodCallExpr call) {
        return READ_METHODS.contains(call.getNameAsString()) && call.getArguments().isEmpty()
                && call.getScope().isPresent();
    }

    /**
     * values.stream().map(value -> ...), sizes.forEach(size -> ...): лямбда получает элемент данных. У Optional
     * лямбда выполняется над одним значением неизвестного происхождения, а элемент IntStream.range(0, n) - счетчик
     */
    private boolean isElementLambda(LambdaExpr lambda) {
        Node parent = lambda.getParentNode().orElse(null);
        if (!(parent instanceof MethodCallExpr iteration) || !ELEMENT_METHODS.contains(iteration.getNameAsString())
                || Loops.enclosingIteration(lambda.getBody()).filter(found -> found == lambda).isEmpty()) {
            return false;
        }
        Optional<Expression> scope = iteration.getScope();
        while (scope.isPresent() && Nodes.unwrap(scope.get()).isMethodCallExpr()) {
            MethodCallExpr call = Nodes.unwrap(scope.get()).asMethodCallExpr();
            if (COUNTER_SOURCES.contains(call.getNameAsString())) {
                return false;
            }
            scope = call.getScope();
        }
        return true;
    }

    private boolean isForEachVariable(VariableDeclarator variable) {
        return variable.getParentNode()
                .flatMap(Node::getParentNode)
                .filter(ForEachStmt.class::isInstance)
                .isPresent();
    }

    private boolean isOneOf(Expression expression, Set<String> names) {
        Expression value = strip(expression);
        return value.isNameExpr() && names.contains(value.asNameExpr().getNameAsString());
    }

    // count > 0, count != null, count == 1 значение сверху не ограничивают
    private boolean isLimit(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isNullLiteralExpr()) {
            return false;
        }
        Optional<Object> constant = ConstantValues.evaluate(value);
        return constant.isEmpty() || !(constant.get() instanceof Number number)
                || Math.abs(number.longValue()) > MAX_NOT_A_LIMIT;
    }

    public boolean isConstant(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isLiteralExpr()) {
            return true;
        }
        if (value.isUnaryExpr()) {
            return isConstant(value.asUnaryExpr().getExpression());
        }
        // MAX_SIZE, Limits.MAX_SIZE
        String text = value.toString();
        return (value.isNameExpr() || value.isFieldAccessExpr()) && text.equals(text.toUpperCase())
                && text.chars().anyMatch(Character::isLetter);
    }

    private boolean isSkipped(Node node, Node ignored) {
        return ignored != null && (node == ignored || Nodes.isInside(node, ignored));
    }

    private boolean isLoopCondition(BinaryExpr comparison) {
        Node current = comparison;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().get();
            if (parent instanceof ForStmt loop) {
                return loop.getCompare().filter(compare -> compare == comparison
                        || Nodes.isInside(comparison, compare)).isPresent();
            }
            if (parent instanceof WhileStmt loop) {
                return loop.getCondition() == comparison || Nodes.isInside(comparison, loop.getCondition());
            }
            if (parent instanceof DoStmt loop) {
                return loop.getCondition() == comparison || Nodes.isInside(comparison, loop.getCondition());
            }
            if (!(parent instanceof Expression)) {
                return false;
            }
            current = parent;
        }
        return false;
    }

    /**
     * @return true, если имя встречается в выражении как отдельная переменная
     */
    public boolean mentions(Node node, String name) {
        return node.findAll(NameExpr.class).stream().anyMatch(used -> used.getNameAsString().equals(name));
    }
}
