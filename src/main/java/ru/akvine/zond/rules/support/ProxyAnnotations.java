package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.Optional;
import java.util.Set;

/**
 * Аннотации, которые выполняет не сам объект, а Spring-прокси вокруг него. Прокси - наследник класса бина,
 * созданный CGLIB: он переопределяет методы и перед вызовом настоящего открывает транзакцию, уводит работу
 * в другой поток, повторяет вызов. Отсюда общие ограничения: вызов должен прийти снаружи, метод должен быть
 * переопределяемым, а бин - уже созданным.
 */
public final class ProxyAnnotations {
    // Меняют то, как метод выполняется: транзакция, другой поток, повтор, кеш, защита от сбоев (Resilience4j),
    // блокировка задачи (ShedLock)
    private static final Set<String> BEHAVIOR = Set.of(
            "Transactional", "Async", "Retryable", "Cacheable", "CachePut", "CacheEvict", "Caching", "Retry",
            "CircuitBreaker", "RateLimiter", "Bulkhead", "TimeLimiter", "SchedulerLock");
    // Проверка прав тоже идет через прокси
    private static final Set<String> SECURITY = Set.of(
            "PreAuthorize", "PostAuthorize", "PreFilter", "PostFilter", "Secured", "RolesAllowed");
    // На классе действуют на все его открытые методы
    private static final Set<String> CLASS_WIDE = Set.of(
            "Transactional", "Async", "Retryable", "Retry", "CircuitBreaker", "RateLimiter", "Bulkhead");
    // Прокси создается для бина целиком: проверка аргументов (@Validated), область видимости с прокси
    private static final Set<String> CLASS_ONLY = Set.of("Validated", "RefreshScope", "RequestScope", "SessionScope");

    private static final Set<String> CACHE = Set.of("Cacheable", "CachePut", "CacheEvict", "Caching");
    private static final Set<String> RETRY = Set.of("Retryable", "Retry");
    private static final String TRANSACTIONAL = "Transactional";
    private static final String ASYNC = "Async";

    private ProxyAnnotations() {
    }

    /**
     * @return аннотация на самом методе, которая меняет его выполнение: @Transactional, @Async, @Retryable, @Cacheable
     */
    public static Optional<AnnotationExpr> behavior(MethodDeclaration method) {
        return Annotations.find(method, BEHAVIOR);
    }

    /**
     * @return аннотация, которая действует на метод: его собственная либо аннотация класса - на открытый метод
     */
    public static Optional<AnnotationExpr> effectiveBehavior(MethodDeclaration method) {
        Optional<AnnotationExpr> own = behavior(method);
        if (own.isPresent() || !method.isPublic()) {
            return own;
        }
        return method.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration)
                .flatMap(parent -> Annotations.find((ClassOrInterfaceDeclaration) parent, CLASS_WIDE));
    }

    /**
     * @return аннотация на методе, из-за которой для бина создается прокси, в том числе проверка прав
     */
    public static Optional<AnnotationExpr> onMethod(MethodDeclaration method) {
        return behavior(method).or(() -> Annotations.find(method, SECURITY));
    }

    /**
     * @return аннотация на классе, из-за которой для бина создается прокси
     */
    public static Optional<AnnotationExpr> onClass(ClassOrInterfaceDeclaration type) {
        return Annotations.find(type, CLASS_WIDE)
                .or(() -> Annotations.find(type, SECURITY))
                .or(() -> Annotations.find(type, CLASS_ONLY));
    }

    /**
     * @return что именно не произойдет, если аннотацию некому выполнить
     */
    public static String consequence(AnnotationExpr annotation) {
        String name = annotation.getName().getIdentifier();
        if (TRANSACTIONAL.equals(name)) {
            return "транзакция не откроется";
        }
        if (ASYNC.equals(name)) {
            return "метод выполнится в том же потоке";
        }
        if (RETRY.contains(name)) {
            return "повторных попыток не будет";
        }
        if (CACHE.contains(name)) {
            return "кеш задействован не будет";
        }
        return "аннотация не сработает";
    }
}
