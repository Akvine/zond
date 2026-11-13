package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class NarrowingCastRule extends AbstractRule {
    private static final String HASH_CODE = "hashCode";
    // Запись числа заданной ширины в буфер или поток: ByteBuf.writeIntLE, ByteBuffer.putShort, DataOutput.writeByte
    private static final Pattern BUFFER_WRITES =
            Pattern.compile("(write|put|set)(Int|Short|Byte|Medium|UnsignedInt|UnsignedShort|UnsignedByte)(LE|BE)?");
    private static final String MATH = "Math";
    private static final String MIN = "min";
    private static final String MAX_VALUE = "MAX_VALUE";
    private static final String MIN_VALUE = "MIN_VALUE";
    private static final String BETWEEN = "between";
    private static final String COUNT = "count";
    private static final Set<String> NARROW_BOUNDS = Set.of("int", "Integer", "short", "Short", "byte", "Byte");
    private static final Set<BinaryExpr.Operator> RELATIONS = Set.of(
            BinaryExpr.Operator.LESS, BinaryExpr.Operator.LESS_EQUALS,
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS);
    private static final String STREAM = "stream";
    private static final Pattern EXPANDING = Pattern.compile("flatMap\\w*|mapMulti\\w*");
    private static final Set<BinaryExpr.Operator> SUMS = Set.of(BinaryExpr.Operator.PLUS, BinaryExpr.Operator.MINUS);
    private static final Set<String> SMALL_UNIT_METHODS = Set.of(
            "toDays", "toHours", "toDaysPart", "toHoursPart", "toMinutesPart", "toSecondsPart", "toMillisPart");
    private static final Set<String> SMALL_UNITS = Set.of("DAYS", "WEEKS", "MONTHS", "YEARS", "HOURS", "DECADES");
    private static final Set<String> NARROW_TYPES = Set.of("int", "short", "byte");
    private static final Set<String> WIDE_TYPES = Set.of("long", "Long");
    private static final Set<AssignExpr.Operator> COMPOUND_OPERATORS = Set.of(
            AssignExpr.Operator.PLUS, AssignExpr.Operator.MINUS, AssignExpr.Operator.MULTIPLY);

    // Остаток от деления, маска и сдвиг заведомо укладывают значение в нужный диапазон
    private static final Set<BinaryExpr.Operator> BOUNDING_OPERATORS = Set.of(
            BinaryExpr.Operator.REMAINDER, BinaryExpr.Operator.BINARY_AND,
            BinaryExpr.Operator.SIGNED_RIGHT_SHIFT, BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT);

    @Override
    public String code() {
        return RuleCodes.NARROWING_CAST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет приведение long к int без проверки диапазона, в том числе скрытое"
                + " в составном присваивании (+=)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>(findExplicit(sourceFile));
        // count += total, где count - int, а total - long: компилятор сам добавляет приведение (int)
        for (AssignExpr assignment : sourceFile.unit().findAll(AssignExpr.class)) {
            if (!COMPOUND_OPERATORS.contains(assignment.getOperator()) || TestClasses.isInside(assignment)) {
                continue;
            }
            Optional<String> target = LocalTypes.typeOf(assignment.getTarget()).filter(NARROW_TYPES::contains);
            if (target.isPresent() && isUnboundedLong(Nodes.unwrap(assignment.getValue()))) {
                violations.add(violation(sourceFile, assignment,
                        "'" + Nodes.text(assignment) + "': long неявно приводится к " + target.get() + " - у составного"
                                + " присваивания приведение скрыто, и компилятор о нем не предупреждает; значение,"
                                + " которое не помещается, молча обрежется; объявите переменную как long либо"
                                + " считайте через Math.addExact / Math.toIntExact"));
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    private List<Violation> findExplicit(SourceFile sourceFile) {
        return sourceFile.unit().findAll(CastExpr.class).stream()
                .filter(cast -> NARROW_TYPES.contains(cast.getType().asString()))
                .filter(cast -> isUnboundedLong(Nodes.unwrap(cast.getExpression())) && !TestClasses.isInside(cast))
                // (int) Math.min(size, limit): значение перед приведением обрезано до предела
                .filter(cast -> !isClamped(Nodes.unwrap(cast.getExpression())) && !isRangeChecked(cast)
                        && !isSmallUnit(Nodes.unwrap(cast.getExpression()))
                        && !isCollectionCount(cast.getExpression(), cast))
                .filter(cast -> !isHashing(cast) && !isWrittenToBuffer(cast))
                .map(cast -> violation(sourceFile, cast,
                        "'" + Nodes.text(cast) + "': long приводится к " + cast.getType() + " без проверки - значение,"
                                + " которое не помещается, молча обрежется и станет другим числом, возможно"
                                + " отрицательным; используйте Math.toIntExact(...) либо проверьте диапазон"))
                .toList();
    }

    // if (sum > Integer.MAX_VALUE) return Integer.MAX_VALUE; return (int) sum;
    // assert indexIsInt(index); return (int) index: предел проверяет assert, как бы он ни был записан
    private boolean isRangeChecked(CastExpr cast) {
        String value = Nodes.unwrap(cast.getExpression()).toString();
        return Guards.isGuarded(cast, check -> {
            String text = check.toString();
            return text.contains(value) && (text.contains(MAX_VALUE) || text.contains(MIN_VALUE)
                    || check.findAncestor(AssertStmt.class).isPresent()) || isComparedWithInt(check, value);
        });
    }

    // blob.length() > maxSize ? maxSize : (int) blob.length(): значение уже сравнили с пределом типа int
    private boolean isComparedWithInt(Expression check, String value) {
        if (!check.isBinaryExpr() || !RELATIONS.contains(check.asBinaryExpr().getOperator())) {
            return false;
        }
        Expression left = Nodes.unwrap(check.asBinaryExpr().getLeft());
        Expression right = Nodes.unwrap(check.asBinaryExpr().getRight());
        return left.toString().equals(value) && isIntBound(right) || right.toString().equals(value) && isIntBound(left);
    }

    // Литерал пределом не считаем: count > 0 проверяет знак, а не то, что число помещается в int
    private boolean isIntBound(Expression bound) {
        return !bound.isLiteralExpr() && LocalTypes.typeOf(bound).filter(NARROW_BOUNDS::contains).isPresent();
    }

    // Число дней или часов в int помещается с запасом: Duration.between(...).toDays(), ChronoUnit.DAYS.between(...)
    private boolean isSmallUnit(Expression expression) {
        if (!expression.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        return SMALL_UNIT_METHODS.contains(call.getNameAsString())
                || BETWEEN.equals(call.getNameAsString()) && call.getScope()
                .filter(scope -> SMALL_UNITS.stream().anyMatch(unit -> scope.toString().endsWith(unit)))
                .isPresent();
    }

    /**
     * long found = items.stream().filter(...).count(); return (int) found: в коллекции не больше Integer.MAX_VALUE
     * элементов, и отобранных из нее - тоже. Сумма таких счетчиков и поправка на константу - то же число элементов.
     * flatMap разворачивает каждый элемент в несколько - там предела уже нет
     */
    private boolean isCollectionCount(Expression expression, Node usage) {
        Expression value = Nodes.unwrap(expression);
        if (value.isBinaryExpr() && SUMS.contains(value.asBinaryExpr().getOperator())) {
            return isCollectionCount(value.asBinaryExpr().getLeft(), usage)
                    && isCollectionCount(value.asBinaryExpr().getRight(), usage);
        }
        if (value.isIntegerLiteralExpr()) {
            return true;
        }
        if (value.isNameExpr()) {
            return !isReassigned(value.asNameExpr(), usage)
                    && LocalTypes.findInitializer(value).filter(initializer -> isStreamCount(initializer)).isPresent();
        }
        return isStreamCount(value);
    }

    private boolean isStreamCount(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isMethodCallExpr() || !COUNT.equals(value.asMethodCallExpr().getNameAsString())
                || !value.asMethodCallExpr().getArguments().isEmpty()) {
            return false;
        }
        List<MethodCallExpr> steps = StreamChains.callsBefore(value.asMethodCallExpr());
        return steps.stream().anyMatch(step -> STREAM.equals(step.getNameAsString()) && step.getArguments().isEmpty())
                && steps.stream().noneMatch(step -> EXPANDING.matcher(step.getNameAsString()).matches());
    }

    private boolean isReassigned(NameExpr variable, Node usage) {
        String name = variable.getNameAsString();
        return Nodes.enclosingCallable(usage).stream()
                .flatMap(callable -> callable.findAll(AssignExpr.class).stream())
                .anyMatch(assignment -> assignment.getTarget().toString().equals(name));
    }

    // return (int) (bits ^ (bits >>> 32)): в hashCode старшие разряды отбрасывают намеренно
    private boolean isHashing(CastExpr cast) {
        return cast.findAncestor(MethodDeclaration.class)
                .filter(method -> HASH_CODE.equals(method.getNameAsString()))
                .isPresent();
    }

    // buffer.writeInt((int) time), output.putShort((short) length): поле двоичного формата именно такой ширины,
    // и число укладывают в него намеренно
    private boolean isWrittenToBuffer(CastExpr cast) {
        Node parent = cast.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent instanceof MethodCallExpr call && call.getScope().isPresent()
                && BUFFER_WRITES.matcher(call.getNameAsString()).matches();
    }

    private boolean isClamped(Expression expression) {
        return expression.isMethodCallExpr() && MethodCalls.isCallOn(expression.asMethodCallExpr(), MATH, MIN);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isUnboundedLong(Expression value) {
        if (value.isBinaryExpr() && BOUNDING_OPERATORS.contains(value.asBinaryExpr().getOperator())) {
            return false;
        }
        return LocalTypes.typeOf(value).filter(WIDE_TYPES::contains).isPresent();
    }
}
