package ru.akvine.zond.rules.codesmell;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.BuildFiles;
import ru.akvine.zond.rules.support.Modules;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TestDependencyInMainScopeRule extends AbstractContextRule {
    // JUnit, Mockito, AssertJ, Hamcrest, Testcontainers и стартеры для тестов
    private static final Pattern TEST_LIBRARY = Pattern.compile(
            "^(junit.*|mockito.*|assertj.*|hamcrest.*|testcontainers|.*-test|.*-testing|rest-assured|awaitility|wiremock.*)$");
    private static final String TESTCONTAINERS_GROUP = "org.testcontainers";
    // Начало имени библиотеки -> пакеты, которые она приносит. Стартер для тестов приносит сразу все
    private static final Map<String, List<String>> LIBRARY_PACKAGES = Map.ofEntries(
            Map.entry("junit", List.of("org.junit", "junit.")),
            Map.entry("mockito", List.of("org.mockito")),
            Map.entry("assertj", List.of("org.assertj")),
            Map.entry("hamcrest", List.of("org.hamcrest")),
            Map.entry("testcontainers", List.of("org.testcontainers")),
            Map.entry("rest-assured", List.of("io.restassured")),
            Map.entry("awaitility", List.of("org.awaitility")),
            Map.entry("wiremock", List.of("com.github.tomakehurst.wiremock", "org.wiremock")),
            Map.entry("reactor-test", List.of("reactor.test")),
            Map.entry("spring-test", List.of("org.springframework.test", "org.springframework.mock")),
            Map.entry("spring-boot-starter-test", List.of("org.junit", "org.mockito", "org.assertj", "org.hamcrest",
                    "org.springframework.test", "org.springframework.mock", "org.springframework.boot.test")));
    // Часть группы, которая годится в имя пакета: ru.acme из ru.acme.acme-commons
    private static final Pattern PACKAGE_PREFIX = Pattern.compile("^[a-z_]\\w*(\\.[a-z_]\\w*)*(?=\\.|$)");
    private static final Pattern MAIN_SOURCES = Pattern.compile("[/\\\\]src[/\\\\]main[/\\\\]");
    private static final Pattern TEST_SEGMENT = Pattern.compile("\\.(test|tests|testing|testkit)(\\.|$)");

    @Override
    public String code() {
        return RuleCodes.TEST_DEPENDENCY_IN_MAIN_SCOPE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует pom.xml и build.gradle и ищет тестовые библиотеки, подключенные к основному коду";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        Map<Path, Set<String>> mainImports = null;
        for (TextFile file : context.textFiles()) {
            for (BuildFiles.Dependency dependency : BuildFiles.dependencies(file, context.folderFiles())) {
                if (isTestLibrary(dependency) && !dependency.isTestOnly() && !dependency.managed()) {
                    if (mainImports == null) {
                        mainImports = mainImportsByModule(context.sources());
                    }
                    Path module = file.path().toAbsolutePath().normalize().getParent();
                    if (isUsedBy(mainImports.getOrDefault(module, Set.of()), dependency)) {
                        continue;
                    }
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

    // Что импортирует основной (не тестовый) код каждого модуля-библиотеки. В приложении тестовой библиотеке
    // в рабочей сборке не место, даже когда основной код на нее ссылается: обычно это импорт по ошибке
    // (org.junit...shadow...Headers вместо своего класса), и находка как раз на него и выведет
    private Map<Path, Set<String>> mainImportsByModule(List<SourceFile> sources) {
        Modules modules = Modules.of(sources);
        Map<Path, Set<String>> imports = new HashMap<>();
        for (SourceFile source : sources) {
            // src/main/java/io/acme/generator/test/Fixtures.java - основной код, хотя пакет и назван test
            if (!MAIN_SOURCES.matcher(source.path().toString()).find() && TestPaths.isTest(source.path())) {
                continue;
            }
            Optional<Path> module = modules.moduleOf(source.path());
            if (module.isPresent() && !modules.isApplication(module.get())) {
                Set<String> names = imports.computeIfAbsent(module.get(), key -> new HashSet<>());
                source.unit().getImports().forEach(declaration -> names.add(declaration.getNameAsString()));
            }
        }
        return imports;
    }

    // Основной код модуля-библиотеки сам пользуется библиотекой: это модуль тестовых утилит (test-util, testkit),
    // который отдает их тестам других модулей. Убрать зависимость в тесты нельзя - модуль перестанет собираться
    private boolean isUsedBy(Set<String> imports, BuildFiles.Dependency dependency) {
        List<String> packages = new ArrayList<>();
        String artifact = dependency.artifact();
        if (TESTCONTAINERS_GROUP.equals(dependency.group())) {
            packages.add(TESTCONTAINERS_GROUP);
        }
        LIBRARY_PACKAGES.forEach((prefix, names) -> {
            if (artifact.startsWith(prefix)) {
                packages.addAll(names);
            }
        });
        for (String prefix : packages) {
            if (imports.stream().anyMatch(name -> name.startsWith(prefix))) {
                return true;
            }
        }
        // spring-security-test -> org.springframework.security.test, acme-commons-test -> com.acme.commons.test
        Matcher groupPrefix = PACKAGE_PREFIX.matcher(dependency.group());
        String group = groupPrefix.find() ? groupPrefix.group() : "";
        return group.contains(".") && imports.stream()
                .anyMatch(name -> name.startsWith(group + ".") && TEST_SEGMENT.matcher(name).find());
    }

    private boolean isTestLibrary(BuildFiles.Dependency dependency) {
        return TESTCONTAINERS_GROUP.equals(dependency.group()) || TEST_LIBRARY.matcher(dependency.artifact()).matches();
    }
}
