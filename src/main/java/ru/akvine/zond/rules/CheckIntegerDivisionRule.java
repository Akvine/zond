package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckIntegerDivisionRule extends AbstractRule {
    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "Double", "Float");
    private static final Set<String> INTEGER_TYPES =
            Set.of("int", "long", "short", "byte", "Integer", "Long", "Short", "Byte");

    // Заведомо целые значения, тип которых понятен без разрешения типов
    private static final Set<String> INTEGER_METHODS = Set.of("size", "length", "count");
    private static final String LENGTH = "length";

    @Override
    public String code() {
        return RuleCodes.CHECK_INTEGER_DIVISION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет целочисленное деление, результат которого записывается в double или float";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (LocalTypes.TypedExpression typed : LocalTypes.findTypedExpressions(sourceFile.unit())) {
            if (!FLOATING_TYPES.contains(typed.targetType())) {
                continue;
            }
            findIntegerDivision(typed.expression()).ifPresent(division -> violations.add(violation(
                    sourceFile, division,
                    "Целочисленное деление '" + division + "' записывается в " + typed.targetType()
                            + ": дробная часть отбрасывается еще до присваивания (5 / 2 = 2, а не 2.5);"
                            + " приведите один из операндов к double")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Идем только по арифметике: в аргументах вызовов и под приведением типа деление уже другого типа
    private Optional<BinaryExpr> findIntegerDivision(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isBinaryExpr()) {
            return Optional.empty();
        }

        BinaryExpr binary = value.asBinaryExpr();
        if (binary.getOperator() == BinaryExpr.Operator.DIVIDE
                && isInteger(binary.getLeft())
                && isInteger(binary.getRight())) {
            return Optional.of(binary);
        }
        return findIntegerDivision(binary.getLeft()).or(() -> findIntegerDivision(binary.getRight()));
    }

    private boolean isInteger(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isMethodCallExpr()) {
            return INTEGER_METHODS.contains(value.asMethodCallExpr().getNameAsString())
                    && value.asMethodCallExpr().getArguments().isEmpty();
        }
        if (value.isFieldAccessExpr() && !value.asFieldAccessExpr().getScope().isThisExpr()) {
            return LENGTH.equals(value.asFieldAccessExpr().getNameAsString());
        }
        return LocalTypes.typeOf(value).filter(INTEGER_TYPES::contains).isPresent();
    }
}
