package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
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
import ru.akvine.zond.rules.support.Loops;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class BoxingInLoopRule extends AbstractRule {
    private static final Set<String> WRAPPER_TYPES = Set.of("Long", "Integer", "Double", "Float", "Short", "Byte");

    @Override
    public String code() {
        return RuleCodes.BOXING_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет накопление в переменной-обертке (Long, Integer) внутри цикла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        // Об одной переменной сообщаем один раз
        Set<Node> reported = new HashSet<>();
        for (Expression expression : sourceFile.unit().findAll(Expression.class)) {
            Optional<Expression> target = accumulatorOf(expression);
            Optional<Node> iteration = Loops.enclosingIteration(expression);
            if (target.isEmpty() || iteration.isEmpty()) {
                continue;
            }
            LocalTypes.findDeclaration(target.get())
                    .filter(declaration -> declaration instanceof VariableDeclarator)
                    .filter(declaration -> Loops.isDeclaredOutside(iteration.get(), declaration))
                    .filter(declaration -> LocalTypes.declaredType(declaration).filter(WRAPPER_TYPES::contains).isPresent())
                    .filter(reported::add)
                    .ifPresent(declaration -> violations.add(violation(sourceFile, expression,
                            "'" + expression + "' в цикле: переменная '" + target.get() + "' объявлена оберткой,"
                                    + " и на каждой итерации значение распаковывается и упаковывается в новый"
                                    + " объект; объявите ее примитивом")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // sum += x, count++, --left
    private Optional<Expression> accumulatorOf(Expression expression) {
        if (expression.isAssignExpr() && expression.asAssignExpr().getOperator() != AssignExpr.Operator.ASSIGN) {
            return Optional.of(expression.asAssignExpr().getTarget()).filter(Expression::isNameExpr);
        }
        if (expression.isUnaryExpr() && isIncrement(expression.asUnaryExpr().getOperator())) {
            return Optional.of(expression.asUnaryExpr().getExpression()).filter(Expression::isNameExpr);
        }
        return Optional.empty();
    }

    private boolean isIncrement(UnaryExpr.Operator operator) {
        return operator == UnaryExpr.Operator.POSTFIX_INCREMENT || operator == UnaryExpr.Operator.PREFIX_INCREMENT
                || operator == UnaryExpr.Operator.POSTFIX_DECREMENT || operator == UnaryExpr.Operator.PREFIX_DECREMENT;
    }
}
