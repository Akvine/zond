package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.Type;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@UtilityClass
class SpringBeans {
    private final static String LAZY = "Lazy";
    private final static Set<String> STEREOTYPES =
            Set.of("Component", "Service", "Repository", "Controller", "RestController", "Configuration");
    private final static Set<String> INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");

    // Lombok создает конструктор, через который Spring внедряет зависимости
    private final static String REQUIRED_ARGS_CONSTRUCTOR = "RequiredArgsConstructor";
    private final static String ALL_ARGS_CONSTRUCTOR = "AllArgsConstructor";

    // Обертки, которые откладывают получение бина и этим разрывают цикл
    private final static Set<String> DEFERRED_TYPES = Set.of("ObjectProvider", "Provider", "ObjectFactory");

    /**
     * Зависимость бина: тип и то, откладывается ли ее получение (@Lazy, ObjectProvider)
     */
    record Dependency(String type, boolean deferred) {
    }

    boolean isBean(ClassOrInterfaceDeclaration type) {
        return !type.isInterface() && Annotations.hasAny(type, STEREOTYPES);
    }

    /**
     * @return все, что Spring внедряет в бин: параметры конструктора, поля и сеттеры с @Autowired,
     * а при конструкторе от Lombok - поля, которые в него попадают
     */
    List<Dependency> findDependencies(ClassOrInterfaceDeclaration bean) {
        List<Dependency> dependencies = new ArrayList<>();

        // Spring берет единственный конструктор либо помеченный @Autowired; при нескольких - самый полный
        Optional<ConstructorDeclaration> constructor = bean.getConstructors().stream()
                .filter(candidate -> Annotations.hasAny(candidate, INJECTION_ANNOTATIONS))
                .findFirst()
                .or(() -> bean.getConstructors().stream()
                        .max(Comparator.comparingInt(candidate -> candidate.getParameters().size())));
        constructor.ifPresent(found -> found.getParameters()
                .forEach(parameter -> dependencies.add(toDependency(parameter.getType(), parameter))));

        boolean allFields = Annotations.has(bean, ALL_ARGS_CONSTRUCTOR);
        boolean finalFields = Annotations.has(bean, REQUIRED_ARGS_CONSTRUCTOR);
        for (FieldDeclaration field : bean.getFields()) {
            if (field.isStatic()) {
                continue;
            }
            boolean viaLombok = constructor.isEmpty()
                    && (allFields || (finalFields && field.isFinal() && !hasInitializer(field)));
            if (viaLombok || Annotations.hasAny(field, INJECTION_ANNOTATIONS)) {
                field.getVariables().forEach(variable -> dependencies.add(toDependency(variable.getType(), field)));
            }
        }

        for (MethodDeclaration method : bean.getMethods()) {
            if (Annotations.hasAny(method, INJECTION_ANNOTATIONS)) {
                for (Parameter parameter : method.getParameters()) {
                    dependencies.add(toDependency(parameter.getType(), parameter));
                }
            }
        }
        return dependencies;
    }

    private boolean hasInitializer(FieldDeclaration field) {
        return field.getVariables().stream().anyMatch(variable -> variable.getInitializer().isPresent());
    }

    private Dependency toDependency(Type type, NodeWithAnnotations<?> annotated) {
        String name = LocalTypes.typeName(type);
        boolean deferred = Annotations.has(annotated, LAZY) || DEFERRED_TYPES.contains(name);

        // ObjectProvider<OrderService>, List<Handler>: зависимость - это тип внутри обертки
        if (type.isClassOrInterfaceType()) {
            Optional<String> argument = type.asClassOrInterfaceType().getTypeArguments()
                    .filter(arguments -> arguments.size() == 1)
                    .map(arguments -> LocalTypes.typeName(arguments.get(0)));
            if (argument.isPresent()) {
                return new Dependency(argument.get(), deferred);
            }
        }
        return new Dependency(name, deferred);
    }
}
