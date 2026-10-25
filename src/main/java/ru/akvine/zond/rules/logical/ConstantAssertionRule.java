package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.AssertMessages;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Set;

@Component
public class ConstantAssertionRule extends AbstractRule {
    private static final String ASSERT_TRUE = "assertTrue";
    private static final String ASSERT_FALSE = "assertFalse";
    private static final String ASSERT_THAT = "assertThat";
    private static final Set<String> COMPARING_ASSERTIONS = Set.of("assertEquals", "assertSame");

    @Override
    public String code() {
        return RuleCodes.CONSTANT_ASSERTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует тесты и ищет проверки, которые сравнивают константы и не могут упасть";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(this::checksConstant)
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' проверяет константу: результат известен заранее и от проверяемого кода"
                                + " не зависит; проверяйте значение, которое вернул код"))
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

    private boolean checksConstant(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (call.getArguments().isEmpty()) {
            return false;
        }
        Expression first = Nodes.unwrap(call.getArgument(0));
        // assertTrue("metric was not sent", count >= 1): в JUnit 4 первым идет сообщение, а не условие
        if (ASSERT_TRUE.equals(method) || ASSERT_FALSE.equals(method)) {
            Expression condition = call.getArguments().size() > 1 && AssertMessages.isText(first)
                    ? Nodes.unwrap(call.getArgument(1))
                    : first;
            // assertTrue(false) после вызова, который должен бросить, - это fail(): тест падает намеренно
            if (condition.isBooleanLiteralExpr()) {
                return condition.asBooleanLiteralExpr().getValue() == ASSERT_TRUE.equals(method);
            }
            return condition.isLiteralExpr();
        }
        // assertThat("reason", actual, matcher) в Hamcrest: строка - пояснение, а не проверяемое значение
        if (ASSERT_THAT.equals(method)) {
            return call.getArguments().size() == 1 ? first.isLiteralExpr()
                    : !AssertMessages.isText(first) && first.isLiteralExpr();
        }
        if (!COMPARING_ASSERTIONS.contains(method) || call.getArguments().size() < 2) {
            return false;
        }
        // assertEquals("Path not empty", 0, path.size()) в JUnit 4: сообщение, ожидаемое, проверяемое -
        // проверяется вычисленное значение
        boolean messageFirst = call.getArguments().size() >= 3 && AssertMessages.isText(first)
                && !Nodes.unwrap(call.getArgument(2)).isLiteralExpr();
        if (messageFirst) {
            return false;
        }
        // assertEquals(5, 5); третий аргумент - сообщение
        return first.isLiteralExpr() && Nodes.unwrap(call.getArgument(1)).isLiteralExpr();
    }

    // Правило проверяет сами тесты
    @Override
    public boolean appliesToTests() {
        return true;
    }
}
