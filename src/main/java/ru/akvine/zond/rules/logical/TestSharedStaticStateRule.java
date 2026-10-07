package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
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
import java.util.Optional;
import java.util.Set;

@Component
public class TestSharedStaticStateRule extends AbstractRule {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    // Методы, которые выполняются вокруг каждого теста: в них состояние возвращают к исходному
    private static final Set<String> RESET_ANNOTATIONS = Set.of("BeforeEach", "AfterEach", "Before", "After");
    // Порядок тестов задан явно: зависимость от него - осознанное решение
    private static final Set<String> ORDERED = Set.of("TestMethodOrder", "FixMethodOrder");
    private static final Set<String> MUTATORS = Set.of(
            "add", "addAll", "put", "putAll", "remove", "removeAll", "clear", "set", "append", "incrementAndGet",
            "getAndIncrement", "decrementAndGet", "push", "offer", "computeIfAbsent", "merge");

    @Override
    public String code() {
        return RuleCodes.TEST_SHARED_STATIC_STATE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует тесты и ищет статические поля, которые тесты меняют: результат начинает зависеть от порядка запуска";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface() || !TestClasses.isTestClass(type) || TestClasses.annotationNames(type).stream()
                    .anyMatch(ORDERED::contains)) {
                continue;
            }
            for (FieldDeclaration field : type.getFields()) {
                // Поле с аннотацией (@Container, @RegisterExtension, @Mock) заполняет сама среда тестирования
                if (!field.isStatic() || !field.getAnnotations().isEmpty()) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    String name = variable.getNameAsString();
                    boolean reset = methods(type, RESET_ANNOTATIONS).stream().anyMatch(method -> findChange(method, name).isPresent());
                    if (reset) {
                        continue;
                    }
                    methods(type, TEST_ANNOTATIONS).stream()
                            .map(method -> findChange(method, name))
                            .flatMap(Optional::stream)
                            .findFirst()
                            .ifPresent(change -> violations.add(violation(sourceFile, change,
                                    "Тест меняет статическое поле '" + name + "', а перед следующим тестом оно не"
                                            + " возвращается к исходному значению: тесты начинают зависеть друг от"
                                            + " друга и от порядка запуска; сделайте поле нестатическим либо"
                                            + " сбрасывайте его в @BeforeEach")));
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
        return ErrorType.LOGICAL;
    }

    private List<MethodDeclaration> methods(ClassOrInterfaceDeclaration type, Set<String> annotations) {
        return type.getMethods().stream()
                .filter(method -> TestClasses.annotationNames(method).stream().anyMatch(annotations::contains))
                .toList();
    }

    // counter = 1, counter++, ITEMS.add(x)
    private Optional<Node> findChange(MethodDeclaration method, String field) {
        // Локальная переменная с тем же именем заслоняет поле
        boolean shadowed = method.findAll(VariableDeclarator.class).stream()
                .anyMatch(variable -> variable.getNameAsString().equals(field))
                || method.getParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(field));
        if (shadowed) {
            return Optional.empty();
        }
        for (AssignExpr assignment : method.findAll(AssignExpr.class)) {
            if (isField(assignment.getTarget(), field)) {
                return Optional.of(assignment);
            }
        }
        for (UnaryExpr unary : method.findAll(UnaryExpr.class)) {
            boolean changes = unary.getOperator().isPostfix() || unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
                    || unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT;
            if (changes && isField(unary.getExpression(), field)) {
                return Optional.of(unary);
            }
        }
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (MUTATORS.contains(call.getNameAsString())
                    && call.getScope().filter(scope -> isField(scope, field)).isPresent()) {
                return Optional.of(call);
            }
        }
        return Optional.empty();
    }

    private boolean isField(Expression expression, String field) {
        Expression value = Nodes.unwrap(expression);
        if (value.isNameExpr()) {
            return value.asNameExpr().getNameAsString().equals(field);
        }
        return value.isFieldAccessExpr() && value.asFieldAccessExpr().getNameAsString().equals(field);
    }
}
