package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.BeanGraph;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LazyInjectionRule extends AbstractRule implements ProjectRule {
    private static final String LAZY = "Lazy";
    private static final String ARROW = " -> ";
    private static final Set<String> FIELD_INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");
    private static final Set<String> METHOD_INJECTION_ANNOTATIONS = Set.of("Autowired", "Inject", "Bean");

    @Override
    public String code() {
        return RuleCodes.LAZY_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Lazy на внедряемой зависимости, которая через другие бины ведет обратно:"
                + " так прячут циклическую зависимость";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        BeanGraph graph = BeanGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
                if (!LAZY.equals(annotation.getName().getIdentifier())) {
                    continue;
                }
                Optional<String> owner = annotation.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ClassOrInterfaceDeclaration::getNameAsString)
                        .filter(graph::isBean);
                // @Lazy на самом классе или @Bean-методе - это отложенное создание бина, а не обход цикла.
                // @Lazy без обратной зависимости - отложенное получение бина (тяжелый клиент, бин другого
                // режима запуска): цикла, который он бы прятал, нет
                findInjected(annotation.getParentNode().orElse(null)).ifPresent(injected -> owner
                        .flatMap(bean -> graph.resolve(LocalTypes.typeName(injected.type()))
                                .flatMap(dependency -> graph.pathBack(dependency, bean)))
                        .ifPresent(cycle -> violations.add(violation(sourceFile, annotation,
                                "@Lazy на внедряемой зависимости '" + injected.name() + "' прячет циклическую"
                                        + " зависимость бинов " + String.join(ARROW, cycle) + ": ошибка при старте"
                                        + " исчезает, а цикл остается; разорвите цикл, вынеся общую логику"
                                        + " в отдельный бин"))));
            }
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
     * @return имя и тип зависимости, если аннотация стоит на точке внедрения
     */
    private Optional<Injected> findInjected(Node annotated) {
        if (annotated instanceof FieldDeclaration field
                && Annotations.hasAny(field, FIELD_INJECTION_ANNOTATIONS)) {
            return Optional.of(new Injected(field.getVariables().stream()
                    .map(NodeWithSimpleName::getNameAsString)
                    .collect(Collectors.joining(", ")), field.getElementType()));
        }

        if (annotated instanceof Parameter parameter) {
            Node owner = parameter.getParentNode().orElse(null);
            boolean isInjectionPoint = owner instanceof ConstructorDeclaration
                    || (owner instanceof MethodDeclaration method
                    && Annotations.hasAny(method, METHOD_INJECTION_ANNOTATIONS));
            return isInjectionPoint
                    ? Optional.of(new Injected(parameter.getNameAsString(), parameter.getType()))
                    : Optional.empty();
        }
        return Optional.empty();
    }

    private record Injected(String name, Type type) {
    }
}
