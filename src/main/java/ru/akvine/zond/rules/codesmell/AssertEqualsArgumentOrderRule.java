package ru.akvine.zond.rules.codesmell;

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
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AssertEqualsArgumentOrderRule extends AbstractRule {
    private static final Set<String> ASSERTIONS = Set.of(
            "assertEquals", "assertNotEquals", "assertSame", "assertNotSame", "assertArrayEquals", "assertIterableEquals");
    private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final String JUNIT4_ASSERT = "org.junit.Assert";
    // У TestNG порядок обратный: проверяемое значение идет первым
    private static final String TESTNG = "org.testng";
    private static final int WITH_MESSAGE = 3;

    @Override
    public String code() {
        return RuleCodes.ASSERT_EQUALS_ARGUMENT_ORDER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует тесты и ищет assertEquals, в котором ожидаемое и проверяемое значения переставлены местами";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        boolean testNg = imports(sourceFile, TESTNG);
        boolean junit4 = imports(sourceFile, JUNIT4_ASSERT);
        if (testNg) {
            return violations;
        }
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!ASSERTIONS.contains(call.getNameAsString()) || call.getArguments().size() < 2
                    || !TestClasses.isInside(call)) {
                continue;
            }
            // В JUnit 4 сообщение идет первым аргументом, в JUnit 5 - последним
            boolean messageFirst = junit4 && call.getArguments().size() == WITH_MESSAGE
                    && call.getArgument(0).isStringLiteralExpr();
            Expression expected = call.getArgument(messageFirst ? 1 : 0);
            Expression actual = call.getArgument(messageFirst ? 2 : 1);
            if (isConstant(actual) && !isConstant(expected)) {
                violations.add(violation(sourceFile, call,
                        "В " + call.getNameAsString() + "(...) значения переставлены: ожидаемое '" + actual
                                + "' стоит на месте проверяемого. Тест работает, но при падении сообщение"
                                + " назовет ожидаемым то, что вернул код; поменяйте аргументы местами"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean imports(SourceFile sourceFile, String prefix) {
        return sourceFile.unit().getImports().stream()
                .anyMatch(declaration -> declaration.getNameAsString().startsWith(prefix));
    }

    // Литерал, константа или значение перечисления: то, что знают заранее, а не получают от кода
    private boolean isConstant(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isUnaryExpr()) {
            value = value.asUnaryExpr().getExpression();
        }
        if (value.isLiteralExpr() || value.isClassExpr()) {
            return true;
        }
        if (value.isNameExpr()) {
            return CONSTANT_NAME.matcher(value.asNameExpr().getNameAsString()).matches();
        }
        return value.isFieldAccessExpr() && CONSTANT_NAME.matcher(value.asFieldAccessExpr().getNameAsString()).matches();
    }

    // Правило проверяет сами тесты
    @Override
    public boolean appliesToTests() {
        return true;
    }
}
