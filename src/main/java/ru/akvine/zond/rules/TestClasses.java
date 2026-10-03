package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.Set;
import java.util.stream.Collectors;

@UtilityClass
class TestClasses {
    private final static Set<String> TEST_CLASS_ANNOTATIONS = Set.of("ExtendWith", "RunWith");
    private final static Set<String> TEST_METHOD_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    private final static String TEST_SUFFIX = "Test";

    /**
     * @return true, если узел находится внутри тестового класса, в том числе вложенного в тестовый
     */
    boolean isInside(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type && isTestClass(type)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // @SpringBootTest, @WebMvcTest, @DataJpaTest и т.п., @ExtendWith / @RunWith либо тестовые методы внутри
    boolean isTestClass(TypeDeclaration<?> type) {
        boolean annotatedAsTest = annotationNames(type).stream()
                .anyMatch(name -> name.endsWith(TEST_SUFFIX) || TEST_CLASS_ANNOTATIONS.contains(name));
        return annotatedAsTest || type.getMethods().stream()
                .anyMatch(method -> annotationNames(method).stream().anyMatch(TEST_METHOD_ANNOTATIONS::contains));
    }

    // Сравниваем по простому имени, чтобы поймать и короткую, и полную запись аннотации
    Set<String> annotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .collect(Collectors.toSet());
    }
}
