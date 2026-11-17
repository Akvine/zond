package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code int count = Integer.parseInt(argument); return text.repeat(count);},
 * {@code int length = in.readInt(); byte[] body = new byte[length];}: сколько памяти выделить, решает число из
 * данных. Одного значения в два миллиарда хватает, чтобы получить OutOfMemoryError, а вместе с ним - отказ
 * всех запросов, которым в этот момент понадобилась память. Число нужно сравнить с верхней границей до выделения.
 */
@Component
public class UnboundedAllocationSizeRule extends AbstractRule {
    private static final String REPEAT = "repeat";
    // Метод -> номер аргумента с размером, у статических методов. Тип, у которого метод вызван, проверяется отдельно
    private static final Map<String, Integer> SIZED_STATIC_METHODS = Map.of(
            REPEAT, 1, "leftPad", 1, "rightPad", 1, "center", 1, "nCopies", 0, "allocate", 0, "allocateDirect", 0,
            "copyOf", 1, "newArrayListWithCapacity", 0, "newHashMapWithExpectedSize", 0);
    private static final Set<String> SIZED_STATIC_TYPES = Set.of("StringUtils", "Strings", "Collections", "ByteBuffer",
            "CharBuffer", "IntBuffer", "LongBuffer", "Arrays", "Lists", "Maps");
    private static final String ENSURE_CAPACITY = "ensureCapacity";
    // value.pow(n), value.shiftLeft(n): число растет вместе с показателем, а с ним - память и время умножения
    private static final Set<String> GROWING_METHODS = Set.of("pow", "shiftLeft");
    private static final Set<String> BIG_NUMBER_TYPES = Set.of("BigInteger", "BigDecimal");
    private static final String VALUE_OF = "valueOf";
    // new StringBuilder(text) и new ArrayList<>(collection) размер не задают: аргумент считается размером, только
    // если он получен разбором строки или прочитан из потока - у элемента данных тип по коду не виден
    private static final Set<String> SIZED_TYPES = Set.of("StringBuilder", "StringBuffer", "ArrayList", "HashMap",
            "HashSet", "LinkedHashMap", "LinkedHashSet", "ByteArrayOutputStream", "ArrayDeque", "Vector",
            "PriorityQueue", "CharArrayWriter", "ArrayBlockingQueue", "ConcurrentHashMap");
    private static final Set<DataValues.Origin> ANY_DATA =
            Set.of(DataValues.Origin.ELEMENT, DataValues.Origin.PARSED, DataValues.Origin.READ);
    private static final Set<DataValues.Origin> NUMBERS_ONLY =
            Set.of(DataValues.Origin.PARSED, DataValues.Origin.READ);

    @Override
    public String code() {
        return RuleCodes.UNBOUNDED_ALLOCATION_SIZE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет выделение памяти, размер которого задает число из данных без верхней границы:"
                + " repeat, массив, начальный размер коллекции или буфера, показатель степени BigInteger";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        if (TestPaths.isTest(sourceFile.path())) {
            return violations;
        }

        for (MethodCallExpr call : sourceFile.findAll(MethodCallExpr.class)) {
            sizeOf(call)
                    .flatMap(size -> check(sourceFile, call, size, ANY_DATA, "Сколько раз повторить или сколько"
                            + " выделить в '" + shorten(call) + "'"))
                    .ifPresent(violations::add);
            if (isBigNumberGrowth(call)) {
                check(sourceFile, call, call.getArgument(0), ANY_DATA,
                        "Показатель в '" + shorten(call) + "' у числа произвольной длины")
                        .ifPresent(violations::add);
            }
        }
        for (ArrayCreationExpr array : sourceFile.findAll(ArrayCreationExpr.class)) {
            for (ArrayCreationLevel level : array.getLevels()) {
                Optional<Violation> violation = level.getDimension()
                        .flatMap(size -> check(sourceFile, array, size, ANY_DATA, "Размер массива '" + array + "'"));
                if (violation.isPresent()) {
                    violations.add(violation.get());
                    break;
                }
            }
        }
        for (ObjectCreationExpr creation : sourceFile.findAll(ObjectCreationExpr.class)) {
            if (SIZED_TYPES.contains(creation.getType().getNameAsString()) && creation.getArguments().size() == 1) {
                check(sourceFile, creation, creation.getArgument(0), NUMBERS_ONLY,
                        "Начальный размер '" + creation + "'")
                        .ifPresent(violations::add);
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
        return ErrorType.SECURITY;
    }

    private Optional<Expression> sizeOf(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }
        // text.repeat(count), builder.ensureCapacity(size)
        boolean onObject = (REPEAT.equals(method) || ENSURE_CAPACITY.equals(method))
                && call.getArguments().size() == 1;
        if (onObject) {
            return Optional.of(call.getArgument(0));
        }
        Integer position = SIZED_STATIC_METHODS.get(method);
        String type = MethodCalls.receiverName(call.getScope().get());
        return position != null && position < call.getArguments().size() && SIZED_STATIC_TYPES.contains(type)
                ? Optional.of(call.getArgument(position))
                : Optional.empty();
    }

    // BigInteger.valueOf(base).pow(exponent), total.shiftLeft(bits)
    private boolean isBigNumberGrowth(MethodCallExpr call) {
        if (!GROWING_METHODS.contains(call.getNameAsString()) || call.getArguments().size() != 1
                || call.getScope().isEmpty()) {
            return false;
        }
        Expression number = Nodes.unwrap(call.getScope().get());
        boolean created = number.isMethodCallExpr() && VALUE_OF.equals(number.asMethodCallExpr().getNameAsString())
                && number.asMethodCallExpr().getScope()
                .filter(type -> BIG_NUMBER_TYPES.contains(type.toString()))
                .isPresent();
        return created || LocalTypes.typeOf(number).filter(BIG_NUMBER_TYPES::contains).isPresent();
    }

    private Optional<Violation> check(SourceFile sourceFile, Node sink, Expression size,
                                      Set<DataValues.Origin> origins, String what) {
        Optional<DataValues.DataValue> value = DataValues.find(size);
        if (value.isEmpty() || !origins.contains(value.get().origin())
                || DataValues.isBoundedAbove(sink, value.get(), null)) {
            return Optional.empty();
        }
        return Optional.of(violation(sourceFile, sink, what + " задает число из данных (" + value.get().source()
                + "), и сверху его никто не ограничил: одного большого значения хватит, чтобы исчерпать память"
                + " (OutOfMemoryError) и уронить все запросы, которые выполняются рядом; сравните число с верхней"
                + " границей до выделения памяти"));
    }

    private String shorten(MethodCallExpr call) {
        String text = Nodes.unwrap(call).toString();
        int start = text.lastIndexOf('.' + call.getNameAsString() + '(');
        return start < 0 ? text : text.substring(start + 1);
    }
}
