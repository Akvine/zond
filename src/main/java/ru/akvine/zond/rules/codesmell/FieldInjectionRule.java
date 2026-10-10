package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class FieldInjectionRule implements Rule {
    private static final Set<String> UI_CONTROLLER_ANNOTATIONS = Set.of(
            "UiController", "UiDescriptor", "ViewController", "ViewDescriptor", "FragmentDescriptor", "DesignRoot");
    private static final Set<String> UI_CONTROLLER_BASES = Set.of(
            "Screen", "ScreenFragment", "StandardEditor", "StandardLookup", "MasterDetailScreen", "StandardView",
            "StandardListView", "StandardDetailView", "StandardMainView", "Fragment", "AbstractWindow",
            "AbstractEditor", "AbstractLookup", "AbstractFrame");

    private static final Set<String> INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.FIELD_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет внедрение зависимостей через поле вместо конструктора";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            // Статические не учитываем: в них внедрение не работает в принципе, это ловит отдельное правило.
            // В тестах внедрение через поле - обычная практика: экземпляр создает тестовый фреймворк, а не Spring
            if (field.isStatic() || TestClasses.isInside(field) || isCreatedByFramework(field)) {
                continue;
            }

            findInjectionAnnotation(field).ifPresent(annotation -> violations.add(new Violation(
                    errorLevel(),
                    errorType(),
                    code(),
                    name(),
                    sourceFile.path(),
                    field.getBegin().map(position -> position.line).orElse(0),
                    "Внедрение через поле '" + fieldNames(field) + "' (@" + annotation.getName().getIdentifier()
                            + "): используйте внедрение через конструктор - зависимость станет явной,"
                            + " а поле можно будет сделать final")));
        }
        return violations;
    }

    // Экран интерфейса (Jmix, CUBA, Vaadin Flow) создает сам фреймворк конструктором без параметров
    // и заполняет его поля: внедрить зависимость через конструктор там нельзя
    private boolean isCreatedByFramework(FieldDeclaration field) {
        return field.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> Annotations.hasAny(type, UI_CONTROLLER_ANNOTATIONS) || type.getExtendedTypes().stream()
                        .anyMatch(parent -> UI_CONTROLLER_BASES.contains(parent.getNameAsString())))
                .isPresent();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Сравниваем по простому имени, чтобы поймать и короткую, и полную запись аннотации
    private Optional<AnnotationExpr> findInjectionAnnotation(FieldDeclaration field) {
        return field.getAnnotations().stream()
                .filter(annotation -> INJECTION_ANNOTATIONS.contains(annotation.getName().getIdentifier()))
                .findFirst();
    }

    // В одном объявлении может быть несколько полей: A a, b;
    private String fieldNames(FieldDeclaration field) {
        return field.getVariables().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.joining(", "));
    }
}
