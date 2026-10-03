package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.Set;

/**
 * Значения, которые приходят от клиента: параметры контроллера и методы запроса
 */
@UtilityClass
class WebInput {
    private final static Set<String> REQUEST_ANNOTATIONS =
            Set.of("RequestParam", "PathVariable", "RequestHeader", "RequestPart", "CookieValue", "MatrixVariable");

    // request.getParameter("name"), file.getOriginalFilename()
    private final static Set<String> REQUEST_METHODS =
            Set.of("getParameter", "getHeader", "getOriginalFilename", "getQueryString", "getPathInfo");

    /**
     * @return часть выражения, которая содержит данные клиента как есть, либо пусто
     */
    Optional<String> findUserInput(Expression expression) {
        for (MethodCallExpr call : expression.findAll(MethodCallExpr.class)) {
            if (REQUEST_METHODS.contains(call.getNameAsString())) {
                return Optional.of(call.toString());
            }
        }
        for (NameExpr name : expression.findAll(NameExpr.class)) {
            if (isRequestParameter(name)) {
                return Optional.of(name.getNameAsString());
            }
        }
        return Optional.empty();
    }

    // Переменная объявлена параметром метода с @RequestParam, @PathVariable и т.п.
    private boolean isRequestParameter(NameExpr name) {
        Optional<Node> declaration = LocalTypes.findDeclaration(name, name.getNameAsString());
        return declaration
                .filter(node -> node instanceof Parameter parameter && Annotations.hasAny(parameter, REQUEST_ANNOTATIONS))
                .isPresent();
    }
}
