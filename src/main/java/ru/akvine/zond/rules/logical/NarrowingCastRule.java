package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
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
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Set;

@Component
public class NarrowingCastRule extends AbstractRule {
    private static final String MATH = "Math";
    private static final String MIN = "min";
    private static final Set<String> NARROW_TYPES = Set.of("int", "short", "byte");
    private static final Set<String> WIDE_TYPES = Set.of("long", "Long");

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
        return "Сканирует код и ищет приведение long к int без проверки диапазона";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(CastExpr.class).stream()
                .filter(cast -> NARROW_TYPES.contains(cast.getType().asString()))
                .filter(cast -> isUnboundedLong(Nodes.unwrap(cast.getExpression())) && !TestClasses.isInside(cast))
                // (int) Math.min(size, limit): значение перед приведением обрезано до предела
                .filter(cast -> !isClamped(Nodes.unwrap(cast.getExpression())))
                .map(cast -> violation(sourceFile, cast,
                        "'" + Nodes.text(cast) + "': long приводится к " + cast.getType() + " без проверки - значение,"
                                + " которое не помещается, молча обрежется и станет другим числом, возможно"
                                + " отрицательным; используйте Math.toIntExact(...) либо проверьте диапазон"))
                .toList();
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
