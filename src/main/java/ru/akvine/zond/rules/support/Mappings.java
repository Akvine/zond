package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Аннотации Spring MVC, которые связывают метод с адресом: @GetMapping, @RequestMapping и подобные
 */
@UtilityClass
public class Mappings {
    private static final String REQUEST_MAPPING = "RequestMapping";
    private static final String VALUE = "value";
    private static final String PATH = "path";
    private static final String METHOD = "method";

    // Аннотация -> HTTP-метод; у @RequestMapping он задается отдельно
    private static final Map<String, String> HTTP_METHODS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH");

    public Optional<AnnotationExpr> find(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .filter(annotation -> REQUEST_MAPPING.equals(name(annotation)) || HTTP_METHODS.containsKey(name(annotation)))
                .findFirst();
    }

    public boolean isRequestMapping(AnnotationExpr annotation) {
        return REQUEST_MAPPING.equals(name(annotation));
    }

    /**
     * @return GET, POST и т.д.; пустая строка, если у @RequestMapping метод не задан
     */
    public String httpMethod(AnnotationExpr annotation) {
        String byAnnotation = HTTP_METHODS.get(name(annotation));
        if (byAnnotation != null) {
            return byAnnotation;
        }
        // method = RequestMethod.POST -> POST
        return member(annotation, Set.of(METHOD))
                .map(Expression::toString)
                .map(text -> text.substring(text.lastIndexOf('.') + 1))
                .orElse("");
    }

    /**
     * @return адреса из value / path; если адрес не задан - один пустой
     */
    public List<String> paths(AnnotationExpr annotation) {
        List<String> paths = new ArrayList<>();
        Optional<Expression> value = annotation.isSingleMemberAnnotationExpr()
                ? Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                : member(annotation, Set.of(VALUE, PATH));
        value.ifPresent(expression -> {
            if (expression.isArrayInitializerExpr()) {
                expression.asArrayInitializerExpr().getValues()
                        .forEach(item -> StringLiterals.textOf(item).ifPresent(paths::add));
            } else {
                StringLiterals.textOf(expression).ifPresent(paths::add);
            }
        });
        return paths.isEmpty() ? List.of("") : paths;
    }

    /**
     * @return true, если адрес задан не строкой, а константой или выражением: каков он, по коду не узнать
     */
    public boolean hasUnknownPath(AnnotationExpr annotation) {
        Optional<Expression> value = annotation.isSingleMemberAnnotationExpr()
                ? Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue())
                : member(annotation, Set.of(VALUE, PATH));
        return value.isPresent() && paths(annotation).equals(List.of(""));
    }

    public boolean hasMember(AnnotationExpr annotation, Set<String> names) {
        return member(annotation, names).isPresent();
    }

    private Optional<Expression> member(AnnotationExpr annotation, Set<String> names) {
        if (!annotation.isNormalAnnotationExpr()) {
            return Optional.empty();
        }
        return annotation.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> names.contains(pair.getNameAsString()))
                .map(MemberValuePair::getValue)
                .findFirst();
    }

    private String name(AnnotationExpr annotation) {
        return annotation.getName().getIdentifier();
    }
}
