package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Область видимости бина: один экземпляр на все приложение (singleton) либо свой у каждого потребителя,
 * запроса, сессии или шага
 */
@UtilityClass
public class BeanScopes {
    private static final String SCOPE = "Scope";
    private static final Set<String> SCOPE_MEMBERS = Set.of("value", "scopeName");

    // Готовые аннотации областей, в которых экземпляр живет недолго и между потоками не делится.
    // @ServerEndpoint: контейнер WebSocket создает свой экземпляр на каждое подключение, даже рядом с @Component
    private static final Set<String> SHORT_LIVED_ANNOTATIONS =
            Set.of("RequestScope", "SessionScope", "StepScope", "JobScope", "ServerEndpoint");

    // Области, в которых экземпляр остается общим для всего приложения
    private static final List<String> SHARED_SCOPES = List.of("singleton", "application");

    /**
     * @param declaration класс бина либо метод с @Bean
     * @return true, если бин объявлен с областью, отличной от singleton: @Scope("prototype"), @RequestScope и т.п.
     */
    public boolean isShortLived(NodeWithAnnotations<?> declaration) {
        if (Annotations.hasAny(declaration, SHORT_LIVED_ANNOTATIONS)) {
            return true;
        }
        // @Scope("prototype"), @Scope(SCOPE_PROTOTYPE), @Scope(value = WebApplicationContext.SCOPE_REQUEST):
        // название области - последняя часть значения. Весь текст аннотации не годится: в нем есть
        // и WebApplicationContext, и proxyMode
        return Annotations.find(declaration, SCOPE)
                .map(BeanScopes::scopeName)
                .filter(name -> SHARED_SCOPES.stream().noneMatch(name::endsWith))
                .isPresent();
    }

    // "prototype" -> prototype, ConfigurableBeanFactory.SCOPE_PROTOTYPE -> scope_prototype; @Scope без значения -
    // singleton
    private String scopeName(AnnotationExpr scope) {
        Optional<Expression> value = scope.isSingleMemberAnnotationExpr()
                ? Optional.of(scope.asSingleMemberAnnotationExpr().getMemberValue())
                : scope.isNormalAnnotationExpr()
                ? scope.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> SCOPE_MEMBERS.contains(pair.getNameAsString()))
                .map(MemberValuePair::getValue)
                .findFirst()
                : Optional.empty();
        String text = value.map(expression -> StringLiterals.textOf(expression).orElse(expression.toString()))
                .orElse(SHARED_SCOPES.get(0));
        return text.substring(text.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * @return true для класса-бина, экземпляр которого один на все приложение
     */
    public boolean isSingletonBean(ClassOrInterfaceDeclaration type) {
        return SpringBeans.isBean(type) && !isShortLived(type);
    }
}
