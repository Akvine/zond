package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CheckLazyInjectionRule extends AbstractRule {
    private static final String LAZY = "Lazy";
    private static final Set<String> FIELD_INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");
    private static final Set<String> METHOD_INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Bean");

    @Override
    public String code() {
        return RuleCodes.CHECK_LAZY_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Lazy на внедряемых зависимостях: так обычно прячут циклическую зависимость";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
            if (!LAZY.equals(annotation.getName().getIdentifier())) {
                continue;
            }

            // @Lazy на самом классе или @Bean-методе - это отложенное создание бина, а не обход цикла
            findInjectedDependency(annotation.getParentNode().orElse(null))
                    .ifPresent(dependency -> violations.add(violation(sourceFile, annotation,
                            "@Lazy на внедряемой зависимости '" + dependency + "': так обычно обходят циклическую"
                                    + " зависимость между бинами - ошибка при старте исчезает, а цикл остается;"
                                    + " разорвите цикл, вынеся общую логику в отдельный бин")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    /**
     * @return имя зависимости, если аннотация стоит на точке внедрения
     */
    private Optional<String> findInjectedDependency(Node annotated) {
        if (annotated instanceof FieldDeclaration field
                && Annotations.hasAny(field, FIELD_INJECTION_ANNOTATIONS)) {
            return Optional.of(field.getVariables().stream()
                    .map(NodeWithSimpleName::getNameAsString)
                    .collect(Collectors.joining(", ")));
        }

        if (annotated instanceof Parameter parameter) {
            Node owner = parameter.getParentNode().orElse(null);
            boolean isInjectionPoint = owner instanceof ConstructorDeclaration
                    || (owner instanceof MethodDeclaration method
                    && Annotations.hasAny(method, METHOD_INJECTION_ANNOTATIONS));
            return isInjectionPoint ? Optional.of(parameter.getNameAsString()) : Optional.empty();
        }
        return Optional.empty();
    }
}
