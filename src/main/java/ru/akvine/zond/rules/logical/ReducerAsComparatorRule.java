package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Функция выбора вместо компаратора: {@code stream.max(Integer::max)}. Сигнатуры совпадают - два числа на входе,
 * int на выходе, - поэтому код компилируется. Но компаратор должен вернуть знак сравнения, а Integer::max
 * возвращает большее из чисел: любое положительное значение читается как "первый больше", и результат неверен.
 */
@Component
public class ReducerAsComparatorRule extends AbstractRule {
    // Операции, последний аргумент которых - компаратор
    private static final Set<String> COMPARING_OPERATIONS = Set.of("max", "min", "sorted", "sort");
    private static final Set<String> NUMBER_TYPES = Set.of("Integer", "Math", "StrictMath");
    private static final Set<String> SELECTORS = Set.of("max", "min", "sum");
    private static final int BINARY = 2;

    @Override
    public String code() {
        return RuleCodes.REDUCER_AS_COMPARATOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Integer::max, Integer::min и Math::max на месте компаратора в max(), min(),"
                + " sorted() и sort(): результат всегда неверен";
    }

    // Ошибка видна в самом вызове и от данных не зависит
    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            String operation = call.getNameAsString();
            // Math.max(a, b) и reduce(Integer::max) - обычные вызовы; интересен только компаратор у стрима и коллекции
            if (!COMPARING_OPERATIONS.contains(operation) || call.getArguments().isEmpty() || call.getScope().isEmpty()) {
                continue;
            }
            Expression comparator = Nodes.unwrap(call.getArgument(call.getArguments().size() - 1));
            describe(comparator).ifPresent(selector -> violations.add(violation(sourceFile, call.getName(),
                    "'" + operation + "(" + Nodes.text(comparator) + ")': на этом месте нужен компаратор, а "
                            + selector + " возвращает само число, а не знак сравнения. Код компилируется, потому что"
                            + " сигнатуры совпали, но " + operation + "() читает любое положительное значение как"
                            + " «первый больше» - и результат получается неверным; используйте Integer::compare,"
                            + " Comparator.naturalOrder() либо mapToInt(...)." + (operation.startsWith("m") ? operation : "max")
                            + "()")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    /**
     * @return название функции выбора, если она стоит на месте компаратора
     */
    private Optional<String> describe(Expression comparator) {
        // Integer::max, Math::min, Integer::sum
        if (comparator.isMethodReferenceExpr()) {
            MethodReferenceExpr reference = comparator.asMethodReferenceExpr();
            boolean selector = NUMBER_TYPES.contains(reference.getScope().toString())
                    && SELECTORS.contains(reference.getIdentifier());
            return selector ? Optional.of(Nodes.text(reference)) : Optional.empty();
        }
        if (!comparator.isLambdaExpr() || comparator.asLambdaExpr().getParameters().size() != BINARY) {
            return Optional.empty();
        }
        LambdaExpr lambda = comparator.asLambdaExpr();
        Optional<Expression> body = lambda.getExpressionBody().map(Nodes::unwrap);
        // (a, b) -> Math.max(a, b)
        boolean selectorCall = body.filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> SELECTORS.contains(call.getNameAsString()) && call.getArguments().size() == BINARY)
                .filter(call -> call.getScope().filter(scope -> NUMBER_TYPES.contains(scope.toString())).isPresent())
                .isPresent();
        // (a, b) -> a > b ? a : b
        Set<String> parameters = Set.of(
                lambda.getParameter(0).getNameAsString(), lambda.getParameter(1).getNameAsString());
        boolean picksOperand = body.filter(Expression::isConditionalExpr)
                .map(Expression::asConditionalExpr)
                .filter(choice -> parameters.contains(Nodes.unwrap(choice.getThenExpr()).toString())
                        && parameters.contains(Nodes.unwrap(choice.getElseExpr()).toString()))
                .isPresent();
        return selectorCall || picksOperand ? Optional.of("лямбда '" + Nodes.text(lambda) + "'") : Optional.empty();
    }
}
