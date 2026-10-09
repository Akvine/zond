package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Область видимости бина: один экземпляр на все приложение (singleton) либо свой у каждого потребителя,
 * запроса, сессии или шага
 */
@UtilityClass
public class BeanScopes {
    private static final String SCOPE = "Scope";

    // Готовые аннотации областей, в которых экземпляр живет недолго и между потоками не делится
    private static final Set<String> SHORT_LIVED_ANNOTATIONS =
            Set.of("RequestScope", "SessionScope", "StepScope", "JobScope");

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
        // название области видно в тексте аннотации при любой записи
        return Annotations.find(declaration, SCOPE)
                .map(AnnotationExpr::toString)
                .map(text -> text.toLowerCase(Locale.ROOT))
                .filter(text -> SHARED_SCOPES.stream().noneMatch(text::contains))
                .isPresent();
    }

    /**
     * @return true для класса-бина, экземпляр которого один на все приложение
     */
    public boolean isSingletonBean(ClassOrInterfaceDeclaration type) {
        return SpringBeans.isBean(type) && !isShortLived(type);
    }
}
