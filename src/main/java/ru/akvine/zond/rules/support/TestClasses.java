package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import lombok.experimental.UtilityClass;

import java.util.Set;

@UtilityClass
public class TestClasses {
    private final static Set<String> TEST_CLASS_ANNOTATIONS = Set.of("ExtendWith", "RunWith");
    private final static Set<String> TEST_METHOD_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    private final static String TEST_SUFFIX = "Test";

    /**
     * @return true, если узел находится внутри тестового класса, в том числе вложенного в тестовый
     */
    public boolean isInside(Node node) {
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
    // Вопрос задают о каждом проверяемом узле, поэтому здесь нет ни стримов, ни промежуточных коллекций
    public boolean isTestClass(TypeDeclaration<?> type) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            String name = annotation.getName().getIdentifier();
            if (name.endsWith(TEST_SUFFIX) || TEST_CLASS_ANNOTATIONS.contains(name)) {
                return true;
            }
        }
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration method && isTestMethod(method)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTestMethod(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            if (TEST_METHOD_ANNOTATIONS.contains(annotation.getName().getIdentifier())) {
                return true;
            }
        }
        return false;
    }

    // Сравниваем по простому имени, чтобы поймать и короткую, и полную запись аннотации
    public Set<String> annotationNames(NodeWithAnnotations<?> node) {
        return Annotations.names(node);
    }
}
