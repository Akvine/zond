package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckNonShortCircuitLogicRule extends AbstractRule {
    private static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "Boolean");
    private static final Set<BinaryExpr.Operator> BOOLEAN_OPERATORS = Set.of(
            BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.LESS,
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER_EQUALS,
            BinaryExpr.Operator.AND, BinaryExpr.Operator.OR);

    // Методы, которые по имени возвращают boolean: isEmpty(), hasNext(), contains(...), equals(...)
    private static final Pattern BOOLEAN_METHOD =
            Pattern.compile("^(is|has|can|should)[A-Z].*|^(contains|equals|matches|exists|startsWith|endsWith).*");

    @Override
    public String code() {
        return RuleCodes.CHECK_NON_SHORT_CIRCUIT_LOGIC_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет & и | между логическими условиями вместо && и ||";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(BinaryExpr.class).stream()
                .filter(expression -> expression.getOperator() == BinaryExpr.Operator.BINARY_AND
                        || expression.getOperator() == BinaryExpr.Operator.BINARY_OR)
                .filter(expression -> isCondition(expression.getLeft()) && isCondition(expression.getRight()))
                .map(expression -> violation(sourceFile, expression,
                        "'" + expression + "': " + expression.getOperator().asString() + " вычисляет обе части"
                                + " всегда - даже когда результат уже ясен; если вторая часть рассчитывает"
                                + " на первую (проверка на null, границы), она упадет; используйте "
                                + expression.getOperator().asString().repeat(2)))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isCondition(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isBooleanLiteralExpr() || value.isInstanceOfExpr()) {
            return true;
        }
        if (value.isBinaryExpr()) {
            return BOOLEAN_OPERATORS.contains(value.asBinaryExpr().getOperator());
        }
        if (value.isUnaryExpr()) {
            return value.asUnaryExpr().getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT;
        }
        if (value.isMethodCallExpr() && BOOLEAN_METHOD.matcher(value.asMethodCallExpr().getNameAsString()).matches()) {
            return true;
        }
        return LocalTypes.typeOf(value).filter(BOOLEAN_TYPES::contains).isPresent();
    }
}
