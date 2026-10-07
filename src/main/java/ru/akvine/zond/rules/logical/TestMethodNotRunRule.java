package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
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
import java.util.regex.Pattern;

@Component
public class TestMethodNotRunRule extends AbstractRule {
    private static final Set<String> TEST_ANNOTATIONS =
            Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");
    // testSavesOrder, shouldSaveOrder: так называют тесты, а не вспомогательные методы
    private static final Pattern TEST_NAME = Pattern.compile("(test|should)[A-Z_].*");
    // В JUnit 3 тесты находятся по имени, аннотация им не нужна
    private static final String JUNIT3_BASE = "TestCase";

    @Override
    public String code() {
        return RuleCodes.TEST_METHOD_NOT_RUN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует тесты и ищет тестовые методы, которые не запускаются: закрытые, статические либо без аннотации @Test";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            boolean junit3 = type.getExtendedTypes().stream()
                    .anyMatch(parent -> JUNIT3_BASE.equals(parent.getNameAsString()));
            if (type.isInterface() || junit3 || !TestClasses.isTestClass(type)) {
                continue;
            }
            boolean hasTests = type.getMethods().stream().anyMatch(this::isAnnotatedTest);
            for (MethodDeclaration method : type.getMethods()) {
                if (isAnnotatedTest(method)) {
                    if (method.isPrivate() || method.isStatic()) {
                        violations.add(violation(sourceFile, method,
                                "Тест '" + method.getNameAsString() + "' объявлен как "
                                        + (method.isPrivate() ? "private" : "static") + ": JUnit такие методы не"
                                        + " запускает, и тест молча пропускается; уберите модификатор")
                                .withConfidence(Confidence.CONFIRMED));
                    }
                    continue;
                }
                boolean looksLikeTest = hasTests && method.getAnnotations().isEmpty() && method.getParameters().isEmpty()
                        && method.getType().isVoidType() && !method.isPrivate() && !method.isStatic()
                        && TEST_NAME.matcher(method.getNameAsString()).matches();
                if (looksLikeTest && !isCalled(type, method)) {
                    violations.add(violation(sourceFile, method,
                            "Метод '" + method.getNameAsString() + "' назван как тест, но аннотации @Test у него нет"
                                    + " и никто его не вызывает: он не запускается; добавьте @Test"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isAnnotatedTest(MethodDeclaration method) {
        return TestClasses.annotationNames(method).stream().anyMatch(TEST_ANNOTATIONS::contains);
    }

    private boolean isCalled(ClassOrInterfaceDeclaration type, MethodDeclaration method) {
        String name = method.getNameAsString();
        return type.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals(name))
                || type.findAll(MethodReferenceExpr.class).stream().anyMatch(reference -> reference.getIdentifier().equals(name));
    }
}
