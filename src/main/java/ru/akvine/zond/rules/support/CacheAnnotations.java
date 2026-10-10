package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Аннотации кеша Spring: что метод кладет в кеш, что из него убирает и как кеш называется
 */
@UtilityClass
public class CacheAnnotations {
    public static final String CACHEABLE = "Cacheable";
    public static final String CACHE_CONFIG = "CacheConfig";
    // @CachePut заменяет запись свежей - устаревшей она после этого не останется
    public static final Set<String> EVICTING = Set.of("CacheEvict", "CachePut");
    private static final String VALUE = "value";
    private static final String CACHE_NAMES = "cacheNames";

    /**
     * @return аннотации с такими именами на методе или классе, в том числе вложенные в @Caching
     */
    public List<AnnotationExpr> find(NodeWithAnnotations<?> node, Set<String> names) {
        return node.getAnnotations().stream()
                .flatMap(annotation -> annotation.findAll(AnnotationExpr.class).stream())
                .filter(annotation -> names.contains(annotation.getName().getIdentifier()))
                .toList();
    }

    public boolean has(NodeWithAnnotations<?> node, Set<String> names) {
        return !find(node, names).isEmpty();
    }

    /**
     * @return имена кешей из аннотации. Имя, заданное константой, берется как есть: CacheNames.USERS -> USERS.
     * Пусто, если имя в аннотации не указано - тогда действует @CacheConfig класса
     */
    public Set<String> names(AnnotationExpr annotation) {
        Optional<Expression> value = annotation.isSingleMemberAnnotationExpr()
                ? Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                : Queries.member(annotation, VALUE).or(() -> Queries.member(annotation, CACHE_NAMES));
        Set<String> names = new LinkedHashSet<>();
        value.ifPresent(expression -> {
            List<Expression> items = expression.isArrayInitializerExpr()
                    ? List.copyOf(expression.asArrayInitializerExpr().getValues())
                    : List.of(expression);
            items.forEach(item -> names.add(nameOf(item)));
        });
        return names;
    }

    private String nameOf(Expression item) {
        return StringLiterals.textOf(item).orElseGet(() -> {
            String text = item.toString();
            return text.substring(text.lastIndexOf('.') + 1);
        });
    }
}
