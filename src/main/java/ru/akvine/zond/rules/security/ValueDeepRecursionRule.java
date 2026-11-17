package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.DataValues;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code long factorial(long n) { return n <= 1 ? 1 : n * factorial(n - 1); }}: метод вызывает сам себя столько
 * раз, каково значение аргумента. Стек потока вмещает десять-двадцать тысяч таких вызовов, дальше -
 * StackOverflowError. Если рекурсивных вызовов в методе два, как в числах Фибоначчи, беда приходит раньше: время
 * растет экспоненциально, и на аргументе 50 поток занят часами. Аргумент нужно ограничить сверху либо заменить
 * рекурсию циклом.
 */
@Component
public class ValueDeepRecursionRule extends AbstractRule {
    private static final Set<String> NUMBER_TYPES =
            Set.of("int", "long", "short", "byte", "Integer", "Long", "Short", "Byte");
    private static final Set<String> BOUND_ANNOTATIONS = Set.of("Max", "Range", "DecimalMax");
    // retry(attempts - 1), walk(depth - 1): счетчик попыток и глубина обхода задаются кодом и малы
    private static final Set<String> COUNTER_NAMES = Set.of("depth", "level", "attempts", "attempt", "retries",
            "retry", "remaining", "left", "ttl", "indent");
    private static final int EXPONENTIAL_CALLS = 2;

    @Override
    public String code() {
        return RuleCodes.VALUE_DEEP_RECURSION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет рекурсию, глубина которой равна значению числового аргумента и ничем сверху"
                + " не ограничена";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        if (TestPaths.isTest(sourceFile.path())) {
            return violations;
        }

        for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
            if (method.getBody().isEmpty() || method.getParameters().isEmpty() || !isNumeric(method)) {
                continue;
            }
            List<MethodCallExpr> selfCalls = method.getBody().get().findAll(MethodCallExpr.class).stream()
                    .filter(call -> isSelfCall(method, call))
                    .toList();
            for (int index = 0; index < method.getParameters().size(); index++) {
                Parameter parameter = method.getParameter(index);
                int position = index;
                List<MethodCallExpr> descents = selfCalls.stream()
                        .filter(call -> isDecreased(call.getArgument(position), parameter.getNameAsString()))
                        .toList();
                if (descents.isEmpty() || isLimited(parameter, descents.get(0))) {
                    continue;
                }
                MethodCallExpr first = descents.get(0);
                violations.add(violation(sourceFile, first,
                        message(method, parameter, first.getArgument(position), descents.size())));
                break;
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

    /**
     * @param descent аргумент первого рекурсивного вызова: n - 1
     * @param calls   сколько рекурсивных вызовов в методе
     */
    private String message(MethodDeclaration method, Parameter parameter, Expression descent, int calls) {
        String name = parameter.getNameAsString();
        String start = "Метод '" + method.getNameAsString() + "' вызывает сам себя с '" + descent
                + "': глубина рекурсии равна значению '" + name + "', и сверху его никто не ограничил."
                + " На значении в десятки тысяч это StackOverflowError";
        String time = calls >= EXPONENTIAL_CALLS
                ? ", а рекурсивных вызовов здесь " + calls + ", поэтому время растет экспоненциально:"
                        + " уже на значении около 50 поток занят часами"
                : "";
        return start + time + "; проверьте верхнюю границу значения либо замените рекурсию циклом";
    }

    // Рекурсию по числу задают только числа: у обхода структуры (дерева, массива) есть и другие аргументы,
    // и глубину там ограничивает сама структура
    private boolean isNumeric(MethodDeclaration method) {
        return method.getParameters().stream()
                .allMatch(parameter -> NUMBER_TYPES.contains(parameter.getTypeAsString()));
    }

    private boolean isSelfCall(MethodDeclaration method, MethodCallExpr call) {
        if (!call.getNameAsString().equals(method.getNameAsString())
                || call.getArguments().size() != method.getParameters().size()
                || Nodes.isInNestedScope(call, method)) {
            return false;
        }
        return call.getScope()
                .map(scope -> scope.isThisExpr() || scope.isNameExpr()
                        && Character.isUpperCase(scope.asNameExpr().getNameAsString().charAt(0)))
                .orElse(true);
    }

    // n - 1, n - 2, --n
    private boolean isDecreased(Expression argument, String name) {
        Expression value = Nodes.unwrap(argument);
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.MINUS) {
            return Nodes.unwrap(value.asBinaryExpr().getLeft()).toString().equals(name)
                    && DataValues.isConstant(value.asBinaryExpr().getRight());
        }
        return value.isUnaryExpr()
                && (value.asUnaryExpr().getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT
                        || value.asUnaryExpr().getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT)
                && Nodes.unwrap(value.asUnaryExpr().getExpression()).toString().equals(name);
    }

    private boolean isLimited(Parameter parameter, MethodCallExpr descent) {
        String name = parameter.getNameAsString();
        if (COUNTER_NAMES.contains(name) || Annotations.hasAny(parameter, BOUND_ANNOTATIONS)) {
            return true;
        }
        // Границу проверяет сам метод либо, если он закрытый, все, кто его вызывает: там константа
        // или уже ограниченное значение
        DataValues.DataValue value =
                new DataValues.DataValue(name, DataValues.Origin.LONG_PARAMETER, List.of(name));
        return DataValues.isBoundedAbove(descent, value, null);
    }
}
