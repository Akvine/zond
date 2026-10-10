package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.List;
import java.util.Set;

@Component
public class TestWithoutAssertionRule extends AbstractRule {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");

    // Проверки JUnit, AssertJ, Mockito, MockMvc, Awaitility; сюда же попадают свои методы вида assertSaved(...)
    private static final List<String> ASSERTION_PREFIXES =
            List.of("assert", "verify", "expect", "fail", "andExpect", "then", "should", "await", "check");

    // @Test(expected = ...) в JUnit 4 - это тоже проверка
    private static final String EXPECTED = "expected";
    private static final String CONTEXT_LOADS = "contextLoads";

    @Override
    public String code() {
        return RuleCodes.TEST_WITHOUT_ASSERTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет тесты без единой проверки";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.hasAny(method, TEST_ANNOTATIONS))
                // contextLoads() проверяет сам факт запуска контекста Spring - пустое тело там уместно
                .filter(method -> !CONTEXT_LOADS.equals(method.getNameAsString()))
                .filter(method -> method.getBody().isPresent() && !hasAssertion(method))
                .map(method -> violation(sourceFile, method,
                        "Тест '" + method.getNameAsString() + "' ничего не проверяет: он пройдет при любом"
                                + " поведении кода, кроме исключения, и создает видимость покрытия;"
                                + " добавьте проверку результата"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean hasAssertion(MethodDeclaration method) {
        boolean expectsException = Annotations.find(method, TEST_ANNOTATIONS)
                .filter(annotation -> annotation.toString().contains(EXPECTED))
                .isPresent();
        return expectsException || method.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .anyMatch(name -> ASSERTION_PREFIXES.stream().anyMatch(name::startsWith));
    }

    // Правило проверяет сами тесты
    @Override
    public boolean appliesToTests() {
        return true;
    }
}
