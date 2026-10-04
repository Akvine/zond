package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckConstantAssertionRule extends AbstractRule {
    private static final String ASSERT_TRUE = "assertTrue";
    private static final String ASSERT_FALSE = "assertFalse";
    private static final String ASSERT_THAT = "assertThat";
    private static final Set<String> COMPARING_ASSERTIONS = Set.of("assertEquals", "assertSame");

    @Override
    public String code() {
        return RuleCodes.CHECK_CONSTANT_ASSERTION_RULE_CODE;
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
        if (ASSERT_TRUE.equals(method) || ASSERT_FALSE.equals(method) || ASSERT_THAT.equals(method)) {
            return first.isLiteralExpr();
        }
        // assertEquals(5, 5); третий аргумент - сообщение
        return COMPARING_ASSERTIONS.contains(method)
                && call.getArguments().size() >= 2
                && first.isLiteralExpr()
                && Nodes.unwrap(call.getArgument(1)).isLiteralExpr();
    }
}
