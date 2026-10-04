package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckNarrowingCastRule extends AbstractRule {
    private static final Set<String> NARROW_TYPES = Set.of("int", "short", "byte");
    private static final Set<String> WIDE_TYPES = Set.of("long", "Long");

    // Остаток от деления, маска и сдвиг заведомо укладывают значение в нужный диапазон
    private static final Set<BinaryExpr.Operator> BOUNDING_OPERATORS = Set.of(
            BinaryExpr.Operator.REMAINDER, BinaryExpr.Operator.BINARY_AND,
            BinaryExpr.Operator.SIGNED_RIGHT_SHIFT, BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT);

    @Override
    public String code() {
        return RuleCodes.CHECK_NARROWING_CAST_RULE_CODE;
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
                .map(cast -> violation(sourceFile, cast,
                        "'" + cast + "': long приводится к " + cast.getType() + " без проверки - значение,"
                                + " которое не помещается, молча обрежется и станет другим числом, возможно"
                                + " отрицательным; используйте Math.toIntExact(...) либо проверьте диапазон"))
                .toList();
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
