package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class UncheckedResultInTestRule extends AbstractRule {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    private static final Set<String> MOCK_CHECKS = Set.of(
            "verify", "verifyNoInteractions", "verifyNoMoreInteractions", "verifyZeroInteractions");
    // Проверки значения: assertEquals, assertThat, expectNext, andExpect и подобные
    private static final List<String> VALUE_CHECK_PREFIXES = List.of("assert", "expect", "andExpect", "should", "fail");
    // Заготовки теста: их результат - не то, что проверяют
    private static final Set<String> SETUP_CALLS = Set.of(
            "mock", "spy", "when", "given", "forClass", "builder", "build", "of", "now", "randomUUID", "valueOf");

    @Override
    public String code() {
        return RuleCodes.UNCHECKED_RESULT_IN_TEST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует тесты и ищет случаи, когда результат вызова получен, а проверяются только обращения к мокам";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            boolean isTest = TestClasses.annotationNames(method).stream().anyMatch(TEST_ANNOTATIONS::contains);
            List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
            boolean checksMocks = calls.stream().anyMatch(call -> MOCK_CHECKS.contains(call.getNameAsString()));
            boolean checksValues = calls.stream()
                    .anyMatch(call -> VALUE_CHECK_PREFIXES.stream().anyMatch(call.getNameAsString()::startsWith));
            if (!isTest || !checksMocks || checksValues) {
                continue;
            }
            for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
                boolean fromCall = variable.getInitializer()
                        .filter(initializer -> initializer.isMethodCallExpr()
                                && !SETUP_CALLS.contains(initializer.asMethodCallExpr().getNameAsString()))
                        .isPresent();
                if (fromCall && !isUsed(method, variable)) {
                    violations.add(violation(sourceFile, variable,
                            "Результат '" + variable.getNameAsString() + "' получен, но не проверен: тест сверяет"
                                    + " только обращения к мокам и пройдет, даже если метод вернет неверное"
                                    + " значение; добавьте проверку результата"));
                }
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

    private boolean isUsed(MethodDeclaration method, VariableDeclarator variable) {
        return method.findAll(NameExpr.class).stream()
                .anyMatch(name -> name.getNameAsString().equals(variable.getNameAsString()));
    }

    // Правило проверяет сами тесты
    @Override
    public boolean appliesToTests() {
        return true;
    }
}
