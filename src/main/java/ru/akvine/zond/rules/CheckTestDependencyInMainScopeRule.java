package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CheckTestDependencyInMainScopeRule extends AbstractContextRule {
    // JUnit, Mockito, AssertJ, Hamcrest, Testcontainers и стартеры для тестов
    private static final Pattern TEST_LIBRARY = Pattern.compile(
            "^(junit.*|mockito.*|assertj.*|hamcrest.*|testcontainers|.*-test|.*-testing|rest-assured|awaitility|wiremock.*)$");
    private static final String TESTCONTAINERS_GROUP = "org.testcontainers";

    @Override
    public String code() {
        return RuleCodes.CHECK_TEST_DEPENDENCY_IN_MAIN_SCOPE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует pom.xml и build.gradle и ищет тестовые библиотеки, подключенные к основному коду";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            for (BuildFiles.Dependency dependency : BuildFiles.dependencies(file, context.textFiles())) {
                if (isTestLibrary(dependency) && !dependency.isTestOnly() && !dependency.managed()) {
                    violations.add(violation(file.path(), dependency.line(),
                            "Тестовая библиотека " + dependency.coordinates() + " подключена к основному коду"
                                    + " (" + dependency.scope() + "): она попадет в рабочую сборку и увеличит ее,"
                                    + " а с ней и число уязвимостей; ограничьте ее тестами - scope test"
                                    + " либо testImplementation"));
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

    private boolean isTestLibrary(BuildFiles.Dependency dependency) {
        return TESTCONTAINERS_GROUP.equals(dependency.group()) || TEST_LIBRARY.matcher(dependency.artifact()).matches();
    }
}
