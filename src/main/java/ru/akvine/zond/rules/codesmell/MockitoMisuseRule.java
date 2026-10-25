package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Три ошибки Mockito, при которых тест проверяет не то, что кажется:
 * поля с @Mock без MockitoExtension, MockitoJUnitRunner или openMocks(...) остаются null; verify(mock); без вызова
 * метода ничего не проверяет; when(...) у настоящего объекта, созданного через new, вызывает настоящий метод
 */
@Component
public class MockitoMisuseRule extends AbstractRule implements ProjectRule {
    private static final Set<String> MOCK_ANNOTATIONS = Set.of("Mock", "Spy", "Captor", "InjectMocks");
    // Чем заполняются поля с @Mock: расширение JUnit 5, раннер и правило JUnit 4, ручной вызов, контекст Spring
    private static final Pattern MOCKITO_SETUP = Pattern.compile(
            "MockitoExtension|MockitoJUnitRunner|MockitoJUnit\\.rule|openMocks|initMocks|MockitoSettings"
                    + "|SpringExtension|SpringRunner|SpringJUnit4ClassRunner");
    private static final Set<String> SPRING_TESTS = Set.of(
            "SpringBootTest", "WebMvcTest", "DataJpaTest", "JdbcTest", "WebFluxTest", "RestClientTest", "JsonTest",
            "DataMongoTest", "DataRedisTest", "ContextConfiguration", "SpringJUnitConfig", "SpringJUnitWebConfig",
            "QuarkusTest", "MicronautTest");
    private static final String VERIFY = "verify";
    private static final String MOCKITO = "Mockito";
    private static final String WHEN = "when";
    private static final Set<String> SPY_FACTORIES = Set.of("spy", "mock");
    private static final Set<String> MOCKITO_VERIFY_IMPORTS = Set.of(
            "org.mockito.Mockito.verify", "org.mockito.Mockito.*", "org.mockito.BDDMockito.verify",
            "org.mockito.BDDMockito.*");

    @Override
    public String code() {
        return RuleCodes.MOCKITO_MISUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует тесты и ищет ошибки Mockito: @Mock без MockitoExtension или openMocks, verify(mock) без"
                + " вызова метода, when(...) у настоящего объекта";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        Set<String> setupAnnotations = setupAnnotations(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                uninitializedMock(type, classes, setupAnnotations).ifPresent(field -> violations.add(violation(
                        sourceFile, field, "Поле '" + field.getVariable(0).getNameAsString() + "' с @"
                                + Annotations.find(field, MOCK_ANNOTATIONS).map(annotation -> annotation.getName()
                                .getIdentifier()).orElse("Mock") + " никто не заполнит: у класса"
                                + " нет @ExtendWith(MockitoExtension.class), @RunWith(MockitoJUnitRunner.class) или"
                                + " MockitoAnnotations.openMocks(this), и в тесте будет NullPointerException вместо"
                                + " проверки; добавьте расширение Mockito")));
            }
            for (ExpressionStmt statement : sourceFile.unit().findAll(ExpressionStmt.class)) {
                if (isBareVerify(statement)) {
                    violations.add(violation(sourceFile, statement,
                            "'" + statement.getExpression() + "' ничего не проверяет: verify возвращает мок, и"
                                    + " проверяется вызов метода, записанный следом; допишите вызов:"
                                    + " verify(mock).save(order)"));
                }
            }
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                realObjectStubbed(call).ifPresent(name -> violations.add(violation(sourceFile, call,
                        "when(...) у '" + name + "', а это настоящий объект, созданный через new: Mockito не сможет"
                                + " подменить ответ и вызовет настоящий метод; сделайте объект моком или"
                                + " spy(...)")));
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
        return ErrorType.CODE_SMELL;
    }

    // Правило проверяет сами тесты
    @Override
    public boolean appliesToTests() {
        return true;
    }

    // @ExtendWith(MockitoExtension.class) внутри своей аннотации проекта: @UnitTest на классе теста
    private Set<String> setupAnnotations(List<SourceFile> sourceFiles) {
        Set<String> names = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (AnnotationDeclaration annotation : sourceFile.unit().findAll(AnnotationDeclaration.class)) {
                boolean setsUp = annotation.getAnnotations().stream()
                        .anyMatch(meta -> MOCKITO_SETUP.matcher(meta.toString()).find()
                                || SPRING_TESTS.contains(meta.getName().getIdentifier()));
                if (setsUp) {
                    names.add(annotation.getNameAsString());
                }
            }
        }
        return names;
    }

    private Optional<FieldDeclaration> uninitializedMock(
            ClassOrInterfaceDeclaration type, ProjectClasses classes, Set<String> setupAnnotations) {
        if (type.isAbstract() || type.isInterface()) {
            return Optional.empty();
        }
        // jdbcTable = mock(JdbcTable.class) в setUp: поле заполняют вручную, аннотация лишь лишняя
        Set<String> assigned = new HashSet<>();
        type.findAll(AssignExpr.class).forEach(assignment -> assigned.add(
                assignment.getTarget().toString().replaceFirst("^this\\.", "")));
        Optional<FieldDeclaration> mock = type.getFields().stream()
                .filter(field -> Annotations.hasAny(field, MOCK_ANNOTATIONS))
                .filter(field -> field.getVariables().stream()
                        .noneMatch(variable -> assigned.contains(variable.getNameAsString())))
                .findFirst();
        if (mock.isEmpty()) {
            return Optional.empty();
        }
        // Класс и его предки из проекта: расширение часто стоит на базовом классе тестов
        Optional<ClassOrInterfaceDeclaration> current = Optional.of(type);
        Set<ClassOrInterfaceDeclaration> visited = new HashSet<>();
        while (current.isPresent() && visited.add(current.get())) {
            if (isSetUp(current.get(), setupAnnotations)) {
                return Optional.empty();
            }
            if (current.get().getExtendedTypes().isNonEmpty() && classes.parent(current.get()).isEmpty()) {
                // Предок из библиотеки: что он настраивает, не видно
                return Optional.empty();
            }
            current = classes.parent(current.get());
        }
        // Вложенный класс @Nested берет расширения у внешнего
        return type.getParentNode().filter(parent -> parent instanceof ClassOrInterfaceDeclaration outer
                && isSetUp(outer, setupAnnotations)).isPresent() ? Optional.empty() : mock;
    }

    private boolean isSetUp(ClassOrInterfaceDeclaration type, Set<String> setupAnnotations) {
        boolean annotated = type.getAnnotations().stream()
                .anyMatch(annotation -> MOCKITO_SETUP.matcher(annotation.toString()).find()
                        || SPRING_TESTS.contains(annotation.getName().getIdentifier())
                        || setupAnnotations.contains(annotation.getName().getIdentifier()));
        boolean manual = type.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> MOCKITO_SETUP.matcher(call.getNameAsString()).matches())
                || type.getFields().stream().anyMatch(field -> MOCKITO_SETUP.matcher(field.toString()).find());
        return annotated || manual;
    }

    // verify(mock); и verify(mock, times(2)); отдельным оператором
    private boolean isBareVerify(ExpressionStmt statement) {
        if (!(statement.getExpression() instanceof MethodCallExpr call) || !VERIFY.equals(call.getNameAsString())) {
            return false;
        }
        // verify из Mockito: Mockito.verify(...) либо статический импорт. verify(1, postRequestedFor(...)) у
        // WireMock и свой метод verify(root) в тесте - другие методы с тем же именем
        boolean mockitoScope = call.getScope().filter(scope -> MOCKITO.equals(scope.toString())).isPresent()
                || call.getScope().isEmpty() && importsMockitoVerify(statement) && !declaresOwnVerify(statement);
        boolean mockArgument = !call.getArguments().isEmpty() && call.getArguments().size() <= 2
                && (call.getArgument(0).isNameExpr() || call.getArgument(0).isFieldAccessExpr());
        return mockitoScope && mockArgument;
    }

    private boolean importsMockitoVerify(ExpressionStmt statement) {
        return statement.findCompilationUnit().stream()
                .flatMap(unit -> unit.getImports().stream())
                .filter(ImportDeclaration::isStatic)
                .map(declaration -> declaration.getNameAsString() + (declaration.isAsterisk() ? ".*" : ""))
                .anyMatch(MOCKITO_VERIFY_IMPORTS::contains);
    }

    private boolean declaresOwnVerify(ExpressionStmt statement) {
        return statement.findCompilationUnit().stream()
                .flatMap(unit -> unit.findAll(MethodDeclaration.class).stream())
                .anyMatch(method -> VERIFY.equals(method.getNameAsString()));
    }

    // Order order = new Order(); when(order.total()).thenReturn(...)
    private Optional<String> realObjectStubbed(MethodCallExpr call) {
        if (!WHEN.equals(call.getNameAsString()) || call.getArguments().size() != 1
                || call.getScope().filter(scope -> !MOCKITO.equals(scope.toString())).isPresent()
                || !(Nodes.unwrap(call.getArgument(0)) instanceof MethodCallExpr stubbed)
                || stubbed.getScope().filter(Expression::isNameExpr).isEmpty()) {
            return Optional.empty();
        }
        Expression receiver = stubbed.getScope().get();
        return LocalTypes.findDeclaration(receiver)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .map(declaration -> (VariableDeclarator) declaration)
                .filter(this::isRealObject)
                .map(VariableDeclarator::getNameAsString);
    }

    private boolean isRealObject(VariableDeclarator variable) {
        boolean annotatedMock = variable.getParentNode()
                .filter(parent -> parent instanceof FieldDeclaration field && Annotations.hasAny(field, MOCK_ANNOTATIONS))
                .isPresent();
        Optional<Expression> initializer = variable.getInitializer().map(Nodes::unwrap);
        // shardingEnvironment = mock(ShardingEnvironment.class) перед when: в переменной уже не тот объект
        String name = variable.getNameAsString();
        boolean reassigned = variable.findAncestor(TypeDeclaration.class).stream()
                .flatMap(type -> type.findAll(AssignExpr.class).stream())
                .anyMatch(assignment -> assignment.getTarget().toString().equals(name)
                        || assignment.getTarget().toString().equals("this." + name));
        return !annotatedMock && !reassigned && initializer.filter(Expression::isObjectCreationExpr).isPresent()
                && initializer.filter(value -> value.findAll(MethodCallExpr.class).stream()
                .noneMatch(inner -> SPY_FACTORIES.contains(inner.getNameAsString()))).isPresent();
    }
}
