package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.Type;
import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * Сам контейнер Spring - ApplicationContext и BeanFactory - в коде приложения.
 */
@UtilityClass
public class SpringContainer {
    // Интерфейсы контейнера и частые реализации
    public final static Set<String> TYPES = Set.of(
            "ApplicationContext", "ConfigurableApplicationContext", "WebApplicationContext",
            "ConfigurableWebApplicationContext", "GenericApplicationContext", "GenericWebApplicationContext",
            "AnnotationConfigApplicationContext", "BeanFactory", "ListableBeanFactory", "HierarchicalBeanFactory",
            "ConfigurableBeanFactory", "ConfigurableListableBeanFactory", "AutowireCapableBeanFactory",
            "DefaultListableBeanFactory");

    private final static Set<String> CONFIGURATION_ANNOTATIONS =
            Set.of("Configuration", "TestConfiguration", "AutoConfiguration", "SpringBootApplication");
    // Классы, которые и есть часть настройки контейнера: им работать с ним напрямую положено
    private final static Set<String> INFRASTRUCTURE = Set.of(
            "BeanPostProcessor", "BeanFactoryPostProcessor", "BeanDefinitionRegistryPostProcessor",
            "ImportBeanDefinitionRegistrar", "FactoryBean", "ApplicationContextInitializer",
            "EnvironmentPostProcessor", "InstantiationAwareBeanPostProcessor",
            "SmartInstantiationAwareBeanPostProcessor", "DestructionAwareBeanPostProcessor");

    public boolean isContainerType(Type type) {
        return TYPES.contains(LocalTypes.typeName(type));
    }

    /**
     * @return true, если класс хранит контейнер в статическом поле - держатель контекста
     */
    public boolean isHolder(TypeDeclaration<?> type) {
        return type.getFields().stream().anyMatch(SpringContainer::isStaticContainer);
    }

    public boolean isStaticContainer(FieldDeclaration field) {
        return field.isStatic() && isContainerType(field.getElementType());
    }

    /**
     * @return true, если узел внутри класса конфигурации, части настройки контейнера
     * (BeanPostProcessor, FactoryBean и другие) либо держателя контекста
     */
    public boolean isInsideInfrastructure(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type && isInfrastructure(type)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean isInfrastructure(TypeDeclaration<?> type) {
        if (Annotations.hasAny(type, CONFIGURATION_ANNOTATIONS) || isHolder(type)) {
            return true;
        }
        return type instanceof ClassOrInterfaceDeclaration declaration
                && (declaration.getImplementedTypes().stream()
                .anyMatch(implemented -> INFRASTRUCTURE.contains(implemented.getNameAsString()))
                || declaration.getExtendedTypes().stream()
                .anyMatch(extended -> INFRASTRUCTURE.contains(extended.getNameAsString())));
    }
}
