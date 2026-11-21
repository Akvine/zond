package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ContextRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.BuildFiles;
import ru.akvine.zond.rules.files.SpringVersions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Аннотация, которая ничего не делает, потому что то, что должно ее заметить, не подключено: нет {@code @Enable...}
 * либо стартер библиотеки рассчитан на другое поколение Spring Boot и его автонастройка не срабатывает
 */
@Component
public class MissingEnableAnnotationRule extends AbstractRule implements ProjectRule, ContextRule {
    // Аннотация на методе -> аннотация, без которой Spring ее не замечает
    private static final Map<String, String> REQUIRED = Map.of(
            "Async", "EnableAsync",
            "Scheduled", "EnableScheduling",
            "Cacheable", "EnableCaching",
            "CacheEvict", "EnableCaching",
            "CachePut", "EnableCaching",
            "Retryable", "EnableRetry");

    // Аннотации resilience4j замечает аспект, который создает автонастройка стартера
    private static final Set<String> RESILIENCE_ANNOTATIONS = Set.of(
            "RateLimiter", "CircuitBreaker", "Retry", "Bulkhead", "TimeLimiter");
    private static final String RESILIENCE_PACKAGE = "io.github.resilience4j";
    private static final String RESILIENCE_GROUP = "io.github.resilience4j";
    private static final String RESILIENCE_STARTER = "resilience4j-spring-boot";
    private static final String BOOT2_STARTER = "resilience4j-spring-boot2";
    private static final String SPRING_CLOUD_STARTER = "spring-cloud-starter-circuitbreaker";
    private static final int JAKARTA_BOOT = 3;
    // Аспекты, собранные в проекте своими руками: аннотации работают и без автонастройки
    private static final List<String> OWN_ASPECTS = List.of(
            "RateLimiterAspect", "CircuitBreakerAspect", "RetryAspect", "BulkheadAspect", "TimeLimiterAspect",
            "resilience4j.springboot3", "ConfigurationOnMissingBean");

    // Настройка приложения попала в проверку - значит, отсутствие @Enable... не случайность выборки файлов
    private static final Set<String> CONFIGURATION_ANNOTATIONS = Set.of("SpringBootApplication", "Configuration");

    @Override
    public String code() {
        return RuleCodes.MISSING_ENABLE_ANNOTATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет @Async, @Scheduled, @Cacheable, @Retryable без включающей их @Enable..."
                + " и аннотации resilience4j при стартере под другое поколение Spring Boot";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return checkProject(List.of(sourceFile));
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>(checkProject(context.sources()));
        violations.addAll(checkResilience(context));
        return violations;
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Set<String> typeAnnotations = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (Node node : sourceFile.findAll(Node.class)) {
                if (node instanceof TypeDeclaration<?> type) {
                    type.getAnnotations().forEach(annotation -> typeAnnotations.add(nameOf(annotation)));
                }
            }
        }

        List<Violation> violations = new ArrayList<>();
        if (typeAnnotations.stream().noneMatch(CONFIGURATION_ANNOTATIONS::contains)) {
            return violations;
        }

        // Об одной недостающей @Enable... сообщаем один раз, на первом методе
        Set<String> reported = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
                for (AnnotationExpr annotation : method.getAnnotations()) {
                    String enable = REQUIRED.get(nameOf(annotation));
                    if (enable != null && !typeAnnotations.contains(enable) && reported.add(enable)) {
                        violations.add(violation(sourceFile, method,
                                "@" + nameOf(annotation) + " на методе '" + method.getNameAsString() + "' не работает:"
                                        + " в проекте нет @" + enable + ", и Spring эту аннотацию не замечает -"
                                        + " метод выполняется как обычный; добавьте @" + enable + " на класс настроек"));
                    }
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    /**
     * {@code @RateLimiter} при resilience4j-spring-boot2 в приложении на Spring Boot 3: автонастройка второго
     * стартера записана в spring.factories, третья версия Boot ее оттуда не читает, и аспект не создается
     */
    private List<Violation> checkResilience(ScanContext context) {
        boolean jakarta = !SpringVersions.boot(context.textFiles()).isEmpty()
                && SpringVersions.boot(context.textFiles()).stream()
                .allMatch(version -> version.major() >= JAKARTA_BOOT);
        Set<String> starters = new HashSet<>();
        for (TextFile file : context.textFiles()) {
            BuildFiles.dependencies(file, context.folderFiles()).stream()
                    .filter(dependency -> !dependency.managed() && !dependency.isTestOnly())
                    .filter(dependency -> RESILIENCE_GROUP.equals(dependency.group())
                            && dependency.artifact().startsWith(RESILIENCE_STARTER)
                            || dependency.artifact().startsWith(SPRING_CLOUD_STARTER))
                    .forEach(dependency -> starters.add(dependency.artifact()));
        }
        boolean ownAspects = context.sources().stream()
                .anyMatch(source -> OWN_ASPECTS.stream().anyMatch(source.text()::contains));
        if (!jakarta || !starters.equals(Set.of(BOOT2_STARTER)) || ownAspects) {
            return List.of();
        }
        long annotated = context.sources().stream()
                .flatMap(source -> source.findAll(MethodDeclaration.class).stream())
                .filter(this::isResilient)
                .count();
        for (SourceFile sourceFile : context.sources()) {
            for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
                if (!isResilient(method)) {
                    continue;
                }
                String annotation = method.getAnnotations().stream()
                        .map(this::nameOf)
                        .filter(RESILIENCE_ANNOTATIONS::contains)
                        .findFirst()
                        .orElseThrow();
                // Об одной причине сообщаем один раз, на первом методе
                return List.of(violation(sourceFile, method,
                        "@" + annotation + " на методе '" + method.getNameAsString() + "' не работает: в сборке"
                                + " стартер " + BOOT2_STARTER + ", а приложение на Spring Boot 3 - автонастройку"
                                + " этого стартера третья версия не подхватывает, аспект аннотаций не создается,"
                                + " и метод выполняется без защиты (таких методов в проекте: " + annotated + ");"
                                + " подключите resilience4j-spring-boot3 и spring-boot-starter-aop")
                        .withLevel(ErrorLevel.MAJOR));
            }
        }
        return List.of();
    }

    private boolean isResilient(MethodDeclaration method) {
        boolean imported = method.findCompilationUnit().stream()
                .flatMap(unit -> unit.getImports().stream())
                .anyMatch(importDeclaration -> importDeclaration.getNameAsString().startsWith(RESILIENCE_PACKAGE));
        return imported
                && method.getAnnotations().stream().map(this::nameOf).anyMatch(RESILIENCE_ANNOTATIONS::contains);
    }

    private String nameOf(AnnotationExpr annotation) {
        return annotation.getName().getIdentifier();
    }
}
