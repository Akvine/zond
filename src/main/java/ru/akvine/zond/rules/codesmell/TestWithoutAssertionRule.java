package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class TestWithoutAssertionRule extends AbstractRule implements ProjectRule {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");

    // Проверки JUnit, AssertJ, Mockito, MockMvc, Awaitility; сюда же попадают свои методы вида assertSaved(...)
    private static final List<String> ASSERTION_PREFIXES =
            List.of("assert", "verify", "expect", "fail", "andExpect", "then", "should", "await", "check");

    // @Test(expected = ...) в JUnit 4 - это тоже проверка
    private static final String EXPECTED = "expected";
    private static final String CONTEXT_LOADS = "contextLoads";
    // testGetMenuDoesNotThrow: тест и задуман как "не падает"
    private static final Pattern NO_EXCEPTION_INTENT =
            Pattern.compile("(?i).*(doesNotThrow|notThrow|noException|withoutException|noError).*");
    // Проверка во вспомогательном методе теста: processTest(config) -> verifyResult(...) -> verify(...)
    private static final int HELPER_DEPTH = 3;

    @Override
    public String code() {
        return RuleCodes.TEST_WITHOUT_ASSERTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет тесты без единой проверки";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        return sourceFiles.stream().flatMap(sourceFile -> check(sourceFile, classes).stream()).toList();
    }

    private List<Violation> check(SourceFile sourceFile, ProjectClasses classes) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.hasAny(method, TEST_ANNOTATIONS))
                // contextLoads() проверяет сам факт запуска контекста Spring - пустое тело там уместно
                .filter(method -> !CONTEXT_LOADS.equals(method.getNameAsString()))
                .filter(method -> !NO_EXCEPTION_INTENT.matcher(method.getNameAsString()).matches())
                .filter(method -> method.getBody().isPresent() && !hasAssertion(method, classes, HELPER_DEPTH))
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

    private boolean hasAssertion(MethodDeclaration method, ProjectClasses classes, int depth) {
        boolean expectsException = Annotations.find(method, TEST_ANNOTATIONS)
                .filter(annotation -> annotation.toString().contains(EXPECTED))
                .isPresent();
        if (expectsException) {
            return true;
        }
        List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
        if (calls.stream().map(MethodCallExpr::getNameAsString)
                .anyMatch(name -> ASSERTION_PREFIXES.stream().anyMatch(name::startsWith))) {
            return true;
        }
        return depth > 0 && calls.stream()
                .filter(call -> call.getScope().filter(scope -> !scope.isThisExpr() && !scope.isSuperExpr()).isEmpty())
                .flatMap(call -> helpersOf(method, call, classes).stream())
                .filter(helper -> helper != method)
                .anyMatch(helper -> hasAssertion(helper, classes, depth - 1));
    }

    // Свой метод с тем же именем и числом аргументов в классе теста либо в его родителях
    private List<MethodDeclaration> helpersOf(MethodDeclaration test, MethodCallExpr call, ProjectClasses classes) {
        List<MethodDeclaration> helpers = new ArrayList<>();
        Optional<ClassOrInterfaceDeclaration> type = test.findAncestor(ClassOrInterfaceDeclaration.class);
        for (int level = 0; type.isPresent() && level <= HELPER_DEPTH; level++) {
            type.get().getMethodsByName(call.getNameAsString()).stream()
                    .filter(candidate -> candidate.getParameters().size() == call.getArguments().size()
                            || candidate.getParameters().stream().anyMatch(Parameter::isVarArgs))
                    .forEach(helpers::add);
            type = type.get().getExtendedTypes().stream().findFirst().flatMap(classes::resolve);
        }
        return helpers;
    }

    // Правило проверяет сами тесты
    @Override
    public boolean appliesToTests() {
        return true;
    }
}
