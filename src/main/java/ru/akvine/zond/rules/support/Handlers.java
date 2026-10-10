package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.Set;

/**
 * Обработчики HTTP-запросов. Адрес и параметры запроса бывают объявлены не на самом методе контроллера,
 * а в интерфейсе, который контроллер реализует: Spring берет аннотации оттуда.
 */
@UtilityClass
public class Handlers {
    // Интерфейс, по которому HTTP-клиента создает библиотека: его методы запросы отправляют, а не принимают
    private static final Set<String> CLIENT_ANNOTATIONS = Set.of(
            "FeignClient", "HttpExchange", "RegisterRestClient", "RetrofitClient");

    /**
     * @param method      метод с кодом обработчика
     * @param mapping     аннотация с адресом и HTTP-методом
     * @param declaration метод, на котором стоят аннотации: сам обработчик либо метод интерфейса
     */
    public record Handler(MethodDeclaration method, AnnotationExpr mapping, MethodDeclaration declaration) {

        /**
         * @return параметр с аннотациями: у обработчика, объявленного в интерфейсе, они стоят там
         */
        public Parameter declared(Parameter parameter) {
            int index = method.getParameters().indexOf(parameter);
            return index >= 0 && index < declaration.getParameters().size() ? declaration.getParameter(index) : parameter;
        }

        /**
         * @return true, если такая аннотация есть на параметре обработчика либо на том же параметре в интерфейсе
         */
        public boolean hasAnnotation(Parameter parameter, Set<String> names) {
            return Annotations.hasAny(parameter, names) || Annotations.hasAny(declared(parameter), names);
        }

        /**
         * @return true, если такая аннотация есть на обработчике либо на его объявлении в интерфейсе
         */
        public boolean hasAnnotation(Set<String> names) {
            return Annotations.hasAny(method, names) || Annotations.hasAny(declaration, names);
        }
    }

    /**
     * @return обработчик, если метод принимает HTTP-запросы: аннотация адреса стоит на нем самом либо на методе
     * интерфейса, который реализует его класс
     */
    public Optional<Handler> of(MethodDeclaration method, ProjectClasses classes) {
        Optional<ClassOrInterfaceDeclaration> owner = method.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration)
                .map(parent -> (ClassOrInterfaceDeclaration) parent);
        if (owner.isEmpty() || method.getBody().isEmpty() || isClient(owner.get())) {
            return Optional.empty();
        }
        Optional<AnnotationExpr> own = Mappings.find(method);
        if (own.isPresent()) {
            return Optional.of(new Handler(method, own.get(), method));
        }
        for (ClassOrInterfaceType implemented : owner.get().getImplementedTypes()) {
            Optional<ClassOrInterfaceDeclaration> contract = classes.resolve(implemented)
                    .filter(found -> found.isInterface() && !isClient(found));
            if (contract.isEmpty()) {
                continue;
            }
            for (MethodDeclaration declared : contract.get().getMethodsByName(method.getNameAsString())) {
                Optional<AnnotationExpr> mapping = Mappings.find(declared);
                if (mapping.isPresent() && declared.getParameters().size() == method.getParameters().size()) {
                    return Optional.of(new Handler(method, mapping.get(), declared));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return true для интерфейса, по которому библиотека создает HTTP-клиента (Feign, HTTP Interface)
     */
    public boolean isClient(ClassOrInterfaceDeclaration type) {
        return type.isInterface() && Annotations.hasAny(type, CLIENT_ANNOTATIONS);
    }
}
