package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Set;

@Component
public class CheckAssertTrueEqualsRule extends AbstractRule {
    private static final String EQUALS = "equals";
    private static final Set<String> BOOLEAN_ASSERTIONS = Set.of("assertTrue", "assertFalse");

    @Override
    public String code() {
        return RuleCodes.CHECK_ASSERT_TRUE_EQUALS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет assertTrue(a.equals(b)) и assertTrue(a == b) вместо assertEquals";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> BOOLEAN_ASSERTIONS.contains(call.getNameAsString()))
                .filter(call -> call.getArguments().stream().anyMatch(this::comparesValues))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': при падении тест сообщит только \"expected true\", без самих значений;"
                                + " используйте assertEquals / assertNotEquals - они покажут ожидаемое и"
                                + " фактическое"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // a.equals(b) либо a == b; сравнение с null оставляем assertNull / assertNotNull
    private boolean comparesValues(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isMethodCallExpr()) {
            return EQUALS.equals(value.asMethodCallExpr().getNameAsString());
        }
        if (!value.isBinaryExpr()) {
            return false;
        }
        BinaryExpr binary = value.asBinaryExpr();
        boolean isEquality = binary.getOperator() == BinaryExpr.Operator.EQUALS
                || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS;
        return isEquality && !binary.getLeft().isNullLiteralExpr() && !binary.getRight().isNullLiteralExpr();
    }
}
