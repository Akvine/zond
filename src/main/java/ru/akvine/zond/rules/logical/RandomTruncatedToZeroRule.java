package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.PrimitiveType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Set;

/**
 * (int) Math.random() * n: приведение к целому применяется к самому случайному числу, а не к произведению.
 * Число от 0 до 1 становится нулем, и все выражение всегда равно нулю.
 */
@Component
public class RandomTruncatedToZeroRule extends AbstractRule {
    private static final Set<PrimitiveType.Primitive> INTEGRAL = Set.of(
            PrimitiveType.Primitive.INT, PrimitiveType.Primitive.LONG, PrimitiveType.Primitive.SHORT,
            PrimitiveType.Primitive.BYTE, PrimitiveType.Primitive.CHAR);
    private static final String RANDOM = "random";
    private static final Set<String> MATH = Set.of("Math", "StrictMath");
    // random.nextDouble() и nextFloat() без границ тоже возвращают число от 0 до 1
    private static final Set<String> UNIT_METHODS = Set.of("nextDouble", "nextFloat");

    @Override
    public String code() {
        return RuleCodes.RANDOM_TRUNCATED_TO_ZERO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет приведение Math.random() к целому до умножения: результат всегда равен нулю";
    }

    // Ошибка видна в самом выражении: число меньше единицы после приведения к целому - ноль
    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(CastExpr.class).stream()
                .filter(cast -> cast.getType().isPrimitiveType() && INTEGRAL.contains(cast.getType().asPrimitiveType().getType()))
                // Произведение в скобках - (int) (Math.random() * n) - приводится целиком, это правильная запись
                .filter(cast -> isUnitRandom(cast.getExpression()))
                .map(cast -> violation(sourceFile, cast, message(cast)))
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

    private boolean isUnitRandom(Expression expression) {
        if (!expression.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        if (!call.getArguments().isEmpty() || call.getScope().isEmpty()) {
            return false;
        }
        String scope = call.getScope().get().toString();
        return RANDOM.equals(call.getNameAsString()) && MATH.stream().anyMatch(scope::endsWith)
                || UNIT_METHODS.contains(call.getNameAsString());
    }

    private String message(CastExpr cast) {
        boolean multiplied = cast.getParentNode()
                .filter(parent -> parent instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.MULTIPLY)
                .isPresent();
        String type = cast.getType().asString();
        String random = cast.getExpression().toString();
        return "'(" + type + ") " + random + "' всегда равно нулю: случайное число от 0 до 1 приводится к целому"
                + (multiplied ? " раньше, чем умножается, и все выражение обращается в ноль" : "")
                + "; возьмите произведение в скобки: (" + type + ") (" + random + " * n)";
    }
}
