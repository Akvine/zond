package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckBigDecimalMisuseRule extends AbstractRule {
    private static final String BIG_DECIMAL = "BigDecimal";
    private static final String EQUALS = "equals";
    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "Double", "Float");

    @Override
    public String code() {
        return RuleCodes.CHECK_BIG_DECIMAL_MISUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет new BigDecimal(double) и сравнение BigDecimal через equals";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (BIG_DECIMAL.equals(creation.getType().getNameAsString())
                    && creation.getArguments().size() == 1
                    && isFloating(creation.getArgument(0))) {
                violations.add(violation(sourceFile, creation,
                        "'" + creation + "': конструктор от double сохраняет двоичную погрешность"
                                + " (0.1 превращается в 0.1000000000000000055...); используйте"
                                + " BigDecimal.valueOf(...) или конструктор от строки"));
            }
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!EQUALS.equals(call.getNameAsString()) || call.getArguments().size() != 1) {
                continue;
            }

            boolean comparesBigDecimal = call.getScope().filter(this::isBigDecimal).isPresent()
                    || isBigDecimal(call.getArgument(0));
            if (comparesBigDecimal) {
                violations.add(violation(sourceFile, call,
                        "Сравнение BigDecimal через equals в '" + call + "': equals учитывает масштаб,"
                                + " 2.0 и 2.00 окажутся не равны; используйте compareTo(...) == 0"));
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

    // 0.1, -0.1, переменная типа double / float
    private boolean isFloating(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isUnaryExpr() && value.asUnaryExpr().getOperator() == UnaryExpr.Operator.MINUS) {
            return isFloating(value.asUnaryExpr().getExpression());
        }
        return LocalTypes.typeOf(value).filter(FLOATING_TYPES::contains).isPresent();
    }

    // Переменная типа BigDecimal, new BigDecimal(...), BigDecimal.ZERO, BigDecimal.valueOf(...)
    private boolean isBigDecimal(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isFieldAccessExpr() && MethodCalls.isType(value.asFieldAccessExpr().getScope(), BIG_DECIMAL)) {
            return true;
        }
        if (value.isMethodCallExpr()
                && value.asMethodCallExpr().getScope()
                .filter(scope -> MethodCalls.isType(scope, BIG_DECIMAL))
                .isPresent()) {
            return true;
        }
        return LocalTypes.typeOf(value).filter(BIG_DECIMAL::equals).isPresent();
    }
}
