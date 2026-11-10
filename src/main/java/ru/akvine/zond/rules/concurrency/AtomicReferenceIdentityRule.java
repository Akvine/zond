package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * compareAndSet у AtomicReference сравнивает ссылки, а не значения. Для Integer, Long, String и BigDecimal два
 * равных значения - обычно разные объекты, и замена молча не срабатывает. Надежно сравнение только с тем самым
 * объектом, который только что прочитан из этой же ссылки.
 */
@Component
public class AtomicReferenceIdentityRule extends AbstractRule {
    private static final Set<String> REFERENCE_TYPES = Set.of(
            "AtomicReference", "AtomicStampedReference", "AtomicMarkableReference");
    private static final Set<String> COMPARING = Set.of(
            "compareAndSet", "weakCompareAndSet", "weakCompareAndSetPlain", "weakCompareAndSetVolatile",
            "compareAndExchange");
    // Значения, которые сравнивают по содержимому. Boolean сюда не входит: Boolean.TRUE и FALSE - всегда одни объекты
    private static final Set<String> VALUE_TYPES = Set.of(
            "Integer", "Long", "Short", "Byte", "Double", "Float", "Character", "String", "BigDecimal", "BigInteger",
            "UUID", "LocalDate", "LocalDateTime", "Instant", "Duration");
    private static final Set<String> NUMBERS = Set.of("Integer", "Long", "Short", "Byte");
    // Чтение текущего объекта из ссылки: с ним сравнение по ссылке честное
    private static final Set<String> READS = Set.of(
            "get", "getPlain", "getAcquire", "getOpaque", "getReference", "getAndSet", "getAndUpdate", "updateAndGet");

    @Override
    public String code() {
        return RuleCodes.ATOMIC_REFERENCE_IDENTITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет compareAndSet у AtomicReference с Integer, Long, String и другими значениями:"
                + " сравниваются ссылки, а не значения";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!COMPARING.contains(call.getNameAsString()) || call.getArguments().size() < 2 || call.getScope().isEmpty()) {
                continue;
            }
            Expression reference = Nodes.unwrap(call.getScope().get());
            Optional<String> value = valueType(reference);
            Expression expected = Nodes.unwrap(call.getArgument(0));
            if (value.isEmpty() || isReadFrom(expected, reference)) {
                continue;
            }
            violations.add(violation(sourceFile, call,
                    "'" + Nodes.text(call) + "' у AtomicReference<" + value.get() + ">: ожидаемое значение"
                            + " сравнивается по ссылке (==), а не через equals. Два равных " + value.get()
                            + " - обычно разные объекты, и замена молча не срабатывает: метод возвращает false,"
                            + " хотя значение то самое; " + (NUMBERS.contains(value.get())
                            ? "для чисел используйте AtomicInteger / AtomicLong" : "храните неизменяемый объект")
                            + ", а ожидаемое значение берите из get() этой же ссылки: T current = ref.get();"
                            + " ref.compareAndSet(current, next)"));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    // AtomicReference<Integer> counter -> Integer
    private Optional<String> valueType(Expression reference) {
        Optional<Node> declaration = LocalTypes.findDeclaration(reference);
        Optional<Type> declared = declaration.filter(found -> found instanceof VariableDeclarator)
                .map(found -> ((VariableDeclarator) found).getType())
                .or(() -> declaration.filter(found -> found instanceof Parameter).map(found -> ((Parameter) found).getType()));
        return declared.filter(Type::isClassOrInterfaceType)
                .map(Type::asClassOrInterfaceType)
                .filter(type -> REFERENCE_TYPES.contains(type.getNameAsString()))
                .flatMap(type -> type.getTypeArguments())
                .filter(arguments -> !arguments.isEmpty())
                .map(arguments -> LocalTypes.typeName(arguments.get(0)))
                .filter(VALUE_TYPES::contains);
    }

    // Integer current = ref.get(); ref.compareAndSet(current, ...): сравнивается тот же объект, что лежит в ссылке
    private boolean isReadFrom(Expression expected, Expression reference) {
        if (!expected.isNameExpr()) {
            return false;
        }
        String holder = Nodes.text(reference);
        boolean initialized = LocalTypes.findInitializer(expected)
                .map(Nodes::unwrap)
                .filter(value -> isRead(value, holder))
                .isPresent();
        String variable = expected.asNameExpr().getNameAsString();
        // current = ref.get() в цикле повторных попыток
        boolean assigned = Nodes.enclosingCallable(expected).stream()
                .flatMap(callable -> callable.findAll(AssignExpr.class).stream())
                .anyMatch(assignment -> assignment.getTarget().toString().equals(variable)
                        && isRead(Nodes.unwrap(assignment.getValue()), holder));
        return initialized || assigned;
    }

    private boolean isRead(Expression value, String holder) {
        return value.isMethodCallExpr() && READS.contains(value.asMethodCallExpr().getNameAsString())
                && value.asMethodCallExpr().getScope().filter(scope -> Nodes.text(Nodes.unwrap(scope)).equals(holder)).isPresent();
    }
}
