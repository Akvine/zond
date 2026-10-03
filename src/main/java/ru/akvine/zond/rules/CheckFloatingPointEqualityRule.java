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
import java.util.Set;

@Component
public class CheckFloatingPointEqualityRule extends AbstractRule {
    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "Double", "Float");

    @Override
    public String code() {
        return RuleCodes.CHECK_FLOATING_POINT_EQUALITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сравнение float и double через == и !=";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr comparison : sourceFile.unit().findAll(BinaryExpr.class)) {
            if (comparison.getOperator() != BinaryExpr.Operator.EQUALS
                    && comparison.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
                continue;
            }

            Expression left = Nodes.unwrap(comparison.getLeft());
            Expression right = Nodes.unwrap(comparison.getRight());

            // x != x - принятый способ проверки на NaN; одинаковые операнды ловит отдельное правило
            if (left.isNullLiteralExpr() || right.isNullLiteralExpr() || left.equals(right)) {
                continue;
            }

            if (isFloating(left) || isFloating(right)) {
                violations.add(violation(sourceFile, comparison,
                        "Сравнение чисел с плавающей точкой через " + comparison.getOperator().asString()
                                + " в '" + comparison + "': из-за погрешности вычислений 0.1 + 0.2 != 0.3;"
                                + " сравнивайте с допуском (Math.abs(a - b) < eps) или используйте BigDecimal"));
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
        return ErrorType.LOGICAL;
    }

    private boolean isFloating(Expression expression) {
        return LocalTypes.typeOf(expression).filter(FLOATING_TYPES::contains).isPresent();
    }
}
