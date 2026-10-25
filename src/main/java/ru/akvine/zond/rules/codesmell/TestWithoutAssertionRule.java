package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
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
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class TestWithoutAssertionRule extends AbstractRule implements ProjectRule {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");

    // Проверки JUnit, AssertJ, Mockito, MockMvc, Awaitility; сюда же попадают свои методы вида assertSaved(...)
    private static final List<String> ASSERTION_PREFIXES =
            List.of("assert", "verify", "expect", "fail", "andExpect", "then", "should", "await", "check");
    // ev.new Scenario().testLookup("x", 1), testExpression(...): метод помощника, который сам проверяет
    private static final Pattern TESTING_HELPER = Pattern.compile("^test[A-Z].*");
    private static final Set<String> DISABLED_ANNOTATIONS = Set.of("Ignore", "Disabled");
    private static final Pattern NO_EXCEPTION_COMMENT = Pattern.compile(
            "(?i)(n[o']t need to assert|without throwing|does(n't| not) throw|no exception|should(n't| not) throw|not crash)");

    // @Test(expected = ...) в JUnit 4 - это тоже проверка
    private static final String EXPECTED = "expected";
    private static final String CONTEXT_LOADS = "contextLoads";
    // testGetMenuDoesNotThrow: тест и задуман как "не падает"
    private static final Pattern NO_EXCEPTION_INTENT =
            Pattern.compile("(?i).*(doesNotThrow|notThrow|noException|withoutException|noError).*");
    // Проверка во вспомогательном методе теста: processTest(config) -> verifyResult(...) -> verify(...)
    private static final int HELPER_DEPTH = 3;
    private static final int MAX_REPORTED_PER_FILE = 10;
    private static final int GENERATED_SUITE_SIZE = 200;
    private static final String OVERRIDE = "Override";

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
        List<MethodDeclaration> tests = sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.hasAny(method, TEST_ANNOTATIONS))
                // contextLoads() проверяет сам факт запуска контекста Spring - пустое тело там уместно
                .filter(method -> !CONTEXT_LOADS.equals(method.getNameAsString()))
                .filter(method -> !NO_EXCEPTION_INTENT.matcher(method.getNameAsString()).matches())
                // @Ignore / @Disabled: тест не выполняется
                .filter(method -> !Annotations.hasAny(method, DISABLED_ANNOTATIONS))
                .filter(method -> !isFixtureOfAnotherTest(method) && !explainsNoAssertion(method))
                // Пустой @Override - так в подклассе отключают унаследованный тест
                .filter(method -> !(Annotations.has(method, OVERRIDE)
                        && method.getBody().filter(body -> body.getStatements().isEmpty()).isPresent()))
                .filter(method -> method.getBody().isPresent() && !hasAssertion(method, classes, HELPER_DEPTH))
                .toList();
        // Набор на сотни тестов в одном файле создан генератором (ClientCompatibilityTest_2_9 в Hazelcast на 985
        // тестов): его пустые тесты - одна находка на файл. В обычном классе тестов каждая находка своя
        long allTests = sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.hasAny(method, TEST_ANNOTATIONS))
                .count();
        if (tests.size() > MAX_REPORTED_PER_FILE && allTests >= GENERATED_SUITE_SIZE) {
            MethodDeclaration first = tests.get(0);
            return List.of(violation(sourceFile, first,
                    tests.size() + " тестов в этом файле ничего не проверяют, первый - '" + first.getNameAsString()
                            + "': они пройдут при любом поведении кода, кроме исключения, и создают видимость"
                            + " покрытия; если файл создан генератором, поправьте шаблон"));
        }
        return tests.stream()
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
                .anyMatch(name -> ASSERTION_PREFIXES.stream().anyMatch(name::startsWith))
                || calls.stream().anyMatch(call -> isOpaqueTestingHelper(method, call, classes))
                || failsOnCondition(method)) {
            return true;
        }
        if (depth <= 0) {
            return false;
        }
        boolean ownHelper = calls.stream()
                .filter(call -> call.getScope().filter(scope -> !scope.isThisExpr() && !scope.isSuperExpr()).isEmpty())
                .flatMap(call -> helpersOf(method, call, classes).stream())
                .filter(helper -> helper != method)
                .anyMatch(helper -> hasAssertion(helper, classes, depth - 1));
        return ownHelper || calls.stream()
                .flatMap(call -> objectHelpersOf(call, classes).stream())
                .filter(helper -> helper != method)
                .anyMatch(helper -> hasAssertion(helper, classes, depth - 1));
    }

    /**
     * testLookup(...) у объекта, созданного тут же (ev.new Scenario().setUp(...).testLookup("x", 1)), либо свой
     * testExpression(...), которого нет в исходниках (метод базового класса из библиотеки): тело не посмотреть,
     * а имя говорит, что метод проверяет сам. jeecgDemoService.testTran() - метод сервиса, а this.testDict(...)
     * проверяется по своему телу
     */
    private boolean isOpaqueTestingHelper(MethodDeclaration test, MethodCallExpr call, ProjectClasses classes) {
        if (!TESTING_HELPER.matcher(call.getNameAsString()).matches()) {
            return false;
        }
        Optional<Expression> scope = call.getScope();
        while (scope.isPresent() && scope.get().isMethodCallExpr()) {
            scope = scope.get().asMethodCallExpr().getScope();
        }
        if (scope.isPresent() && scope.get().isObjectCreationExpr()) {
            return true;
        }
        if (scope.isPresent() && scope.get().isNameExpr()) {
            return isLibraryObject(scope.get(), classes);
        }
        boolean own = scope.isEmpty() || scope.get().isThisExpr() || scope.get().isSuperExpr();
        return own && helpersOf(test, call, classes).isEmpty();
    }

    // NullPointerTester tester = new NullPointerTester(); tester.testAllPublicConstructors(...): тестер из
    // библиотеки сам падает при ошибке. Тип переменной объявлен, и в проекте такого класса нет
    private boolean isLibraryObject(Expression scope, ProjectClasses classes) {
        return LocalTypes.findDeclaration(scope)
                .flatMap(LocalTypes::declaredType)
                .filter(type -> classes.find(type).isEmpty())
                .isPresent();
    }

    // if (result.hasError()) throw result.getError().getException(): тест падает сам, когда условие нарушено
    private boolean failsOnCondition(MethodDeclaration method) {
        return method.findAll(ThrowStmt.class).stream()
                .anyMatch(statement -> statement.findAncestor(IfStmt.class)
                        .filter(branch -> method.isAncestorOf(branch))
                        .isPresent());
    }

    // JacksonSerializationTest.canonicalSerialization(...), helper.runNoLossSmokeTest() и
    // new SerializationTester(...).runTests(): метод другого класса проекта - статический, у поля или переменной,
    // тип которых объявлен, либо у объекта, созданного тут же
    private List<MethodDeclaration> objectHelpersOf(MethodCallExpr call, ProjectClasses classes) {
        Optional<String> owner = call.getScope()
                .filter(scope -> scope.isNameExpr() || scope.isObjectCreationExpr())
                .flatMap(scope -> {
                    if (scope.isObjectCreationExpr()) {
                        return Optional.of(scope.asObjectCreationExpr().getType().getNameAsString());
                    }
                    String name = scope.asNameExpr().getNameAsString();
                    return Character.isUpperCase(name.charAt(0))
                            ? Optional.of(name)
                            : LocalTypes.findDeclaration(scope).flatMap(LocalTypes::declaredType);
                });
        Optional<ClassOrInterfaceDeclaration> type = owner.flatMap(classes::find);
        if (type.isEmpty()) {
            return List.of();
        }
        List<MethodDeclaration> declared = type.get().getMethodsByName(call.getNameAsString()).stream()
                .filter(candidate -> candidate.getParameters().size() == call.getArguments().size()
                        || candidate.getParameters().stream().anyMatch(Parameter::isVarArgs))
                .toList();
        // handler.handle(message): handle унаследован от базового класса и вызывает переопределенные
        // handleMembersViewEvent(...) с assertTrue внутри - проверка в них. Только у тестового двойника:
        // в рабочем классе checkState(...) в переопределенных методах - проверка входа, а не теста
        if (!declared.isEmpty() || !TestClasses.isInside(type.get())) {
            return declared;
        }
        return type.get().getMethods().stream()
                .filter(method -> Annotations.has(method, OVERRIDE))
                .toList();
    }

    // @Test в статическом вложенном классе теста - заготовка для проверки самого запускателя тестов:
    // JUnit4TestXmlListenerTest.PassingTest.alwaysPasses()
    private boolean isFixtureOfAnotherTest(MethodDeclaration method) {
        Optional<ClassOrInterfaceDeclaration> owner = method.findAncestor(ClassOrInterfaceDeclaration.class);
        return owner.filter(ClassOrInterfaceDeclaration::isNestedType)
                .flatMap(nested -> nested.getParentNode())
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration outer && outer.getMethods().stream()
                        .anyMatch(other -> Annotations.hasAny(other, TEST_ANNOTATIONS)))
                .isPresent();
    }

    // "We don't need to assert anything - just make sure it doesn't throw": так задумано
    private boolean explainsNoAssertion(MethodDeclaration method) {
        return Stream.concat(method.getComment().stream(), method.getAllContainedComments().stream())
                .anyMatch(comment -> NO_EXCEPTION_COMMENT.matcher(comment.getContent()).find());
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
