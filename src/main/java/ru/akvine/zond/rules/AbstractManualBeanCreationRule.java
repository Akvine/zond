package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Создание Spring-бина через new: объект не проходит через контейнер, поэтому остается без внедренных
 * зависимостей и без прокси (@Transactional, @Cacheable, @Async на нем не работают).
 */
public abstract class AbstractManualBeanCreationRule extends AbstractRule {
    private static final String BEAN = "Bean";
    private static final Set<String> CONFIGURATION_ANNOTATIONS = Set.of("Configuration", "TestConfiguration");
    private static final Set<String> STEREOTYPE_ANNOTATIONS =
            Set.of("Component", "Service", "Repository", "Controller", "RestController");

    /**
     * Шаблон имени класса, по которому узнается бин. Если класс найден в исходниках проекта,
     * дополнительно проверяется, что на нем есть стереотип Spring
     */
    protected abstract Pattern beanTypeName();

    /**
     * @return имена классов, которые подходят под шаблон, но бинами не являются
     */
    protected abstract Set<String> notBeanTypes();

    /**
     * @return как назвать создаваемый объект в сообщении: "сервис", "репозиторий"
     */
    protected abstract String beanKind();

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> isBeanType(creation.getType().getNameAsString()))
                .filter(creation -> !isPlainClass(sourceFile, creation))
                .filter(creation -> !isInsideBeanDefinition(creation))
                .filter(creation -> !TestClasses.isInside(creation))
                .map(creation -> violation(sourceFile, creation,
                        "'new " + creation.getType().getNameAsString() + "(...)': " + beanKind() + " создается"
                                + " вручную в обход Spring - без внедрения зависимостей и без прокси,"
                                + " @Transactional и подобные аннотации на нем работать не будут;"
                                + " получайте его через внедрение зависимостей"))
                .toList();
    }

    private boolean isBeanType(String typeName) {
        return beanTypeName().matcher(typeName).matches() && !notBeanTypes().contains(typeName);
    }

    // Класс без стереотипа (где бы в проекте он ни был объявлен) и класс из JDK - не бины
    private boolean isPlainClass(SourceFile sourceFile, ObjectCreationExpr creation) {
        if (Types.isJdkType(creation.getType())) {
            return true;
        }
        return Types.projectTypeAnnotations(creation.getType())
                .map(annotations -> annotations.stream().noneMatch(STEREOTYPE_ANNOTATIONS::contains))
                .orElseGet(() -> isDeclaredHereAsPlainClass(sourceFile, creation.getType().getNameAsString()));
    }

    // Класс объявлен в этом же файле без стереотипа - это обычный класс, а не бин
    private boolean isDeclaredHereAsPlainClass(SourceFile sourceFile, String typeName) {
        for (Node node : sourceFile.unit().findAll(Node.class)) {
            if (node instanceof TypeDeclaration<?> type && type.getNameAsString().equals(typeName)) {
                return !Annotations.hasAny(type, STEREOTYPE_ANNOTATIONS);
            }
        }
        return false;
    }

    // @Bean-метод и класс @Configuration - как раз то место, где бины создают через new
    private boolean isInsideBeanDefinition(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration method && Annotations.has(method, BEAN)) {
                return true;
            }
            if (current instanceof TypeDeclaration<?> type && Annotations.hasAny(type, CONFIGURATION_ANNOTATIONS)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
