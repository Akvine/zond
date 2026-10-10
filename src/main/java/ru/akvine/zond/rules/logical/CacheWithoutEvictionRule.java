package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CacheAnnotations;
import ru.akvine.zond.rules.support.ProjectWords;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class CacheWithoutEvictionRule extends AbstractContextRule {
    // Срок жизни записей и готовые менеджеры кеша со своими настройками вытеснения: устаревшее уйдет само
    private static final Set<String> EXPIRATION = Set.of(
            "expireAfterWrite", "expireAfterAccess", "expireAfter", "refreshAfterWrite", "entryTtl",
            "setTimeToLive", "timeToLiveSeconds", "setDefaultExpiration", "setExpiryPolicyFactory",
            "CreatedExpiryPolicy", "withExpiry", "EhCacheCacheManager", "JCacheCacheManager", "HazelcastCacheManager");
    // Свойства, которыми срок жизни либо внешний файл настроек кеша задаются без кода
    private static final List<String> EXPIRATION_PROPERTIES = List.of(
            "spring.cache.redis.time-to-live", "spring.cache.jcache.config", "spring.cache.ehcache.config",
            "spring.cache.couchbase.expiration", "spring.cache.infinispan.config", "spring.cache.hazelcast.config");
    private static final String CAFFEINE_SPEC = "spring.cache.caffeine.spec";
    private static final String EXPIRE = "expire";
    private static final String REFRESH = "refresh";
    private static final String CACHE_TYPE = "spring.cache.type";
    private static final String NO_CACHE = "none";

    // Кеш чистят вручную через менеджер: cacheManager.getCache("users").clear()
    private static final String GET_CACHE = "getCache";
    private static final String GET_CACHE_NAMES = "getCacheNames";

    private record Place(SourceFile source, Node node, String method) {
    }

    @Override
    public String code() {
        return RuleCodes.CACHE_WITHOUT_EVICTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет кеши @Cacheable, которые нигде не сбрасываются и не имеют срока жизни";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Map<String, Place> cached = new LinkedHashMap<>();
        Set<String> evicted = new HashSet<>();
        if (expiresByItself(context) || !collect(context.sources(), cached, evicted)) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        cached.forEach((name, place) -> {
            if (!evicted.contains(name)) {
                violations.add(violation(place.source(), place.node(),
                        "Кеш '" + name + "' только наполняется (метод '" + place.method() + "'): в проекте нет"
                                + " ни @CacheEvict / @CachePut для него, ни срока жизни записей - то, что попало"
                                + " в кеш, останется там до перезапуска, и изменения в базе приложение не увидит;"
                                + " сбрасывайте кеш при изменении данных либо задайте срок жизни"));
            }
        });
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

    /**
     * @return false, если кеши чистят так, что по коду не понять какие: по имени из переменной или все подряд
     */
    private boolean collect(List<SourceFile> sources, Map<String, Place> cached, Set<String> evicted) {
        for (SourceFile source : sources) {
            for (ClassOrInterfaceDeclaration type : source.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (TestClasses.isInside(type)) {
                    continue;
                }
                Set<String> defaults = new HashSet<>();
                CacheAnnotations.find(type, Set.of(CacheAnnotations.CACHE_CONFIG))
                        .forEach(config -> defaults.addAll(CacheAnnotations.names(config)));
                for (MethodDeclaration method : type.getMethods()) {
                    for (AnnotationExpr annotation : CacheAnnotations.find(method, Set.of(CacheAnnotations.CACHEABLE))) {
                        namesOf(annotation, defaults).forEach(name ->
                                cached.putIfAbsent(name, new Place(source, annotation, method.getNameAsString())));
                    }
                    CacheAnnotations.find(method, CacheAnnotations.EVICTING)
                            .forEach(annotation -> evicted.addAll(namesOf(annotation, defaults)));
                }
            }
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                if (GET_CACHE_NAMES.equals(call.getNameAsString())) {
                    return false;
                }
                if (GET_CACHE.equals(call.getNameAsString()) && call.getArguments().size() == 1) {
                    Optional<String> name = StringLiterals.textOf(call.getArgument(0));
                    if (name.isEmpty()) {
                        return false;
                    }
                    evicted.add(name.get());
                }
            }
        }
        return true;
    }

    private Set<String> namesOf(AnnotationExpr annotation, Set<String> defaults) {
        Set<String> own = CacheAnnotations.names(annotation);
        return own.isEmpty() ? defaults : own;
    }

    private boolean expiresByItself(ScanContext context) {
        if (ProjectWords.hasAny(context.sources(), EXPIRATION)) {
            return true;
        }
        return context.configFiles().stream()
                .flatMap(file -> file.properties().stream())
                .anyMatch(this::limitsLifetime);
    }

    private boolean limitsLifetime(ConfigProperty property) {
        String key = property.key().toLowerCase(Locale.ROOT);
        String value = property.value() == null ? "" : property.value().toLowerCase(Locale.ROOT);
        if (key.equals(CACHE_TYPE)) {
            return NO_CACHE.equals(value.trim());
        }
        if (key.equals(CAFFEINE_SPEC)) {
            return value.contains(EXPIRE) || value.contains(REFRESH);
        }
        return EXPIRATION_PROPERTIES.contains(key);
    }
}
