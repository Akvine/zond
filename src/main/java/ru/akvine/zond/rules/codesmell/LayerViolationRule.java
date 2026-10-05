package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.SpringBeans;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class LayerViolationRule extends AbstractRule implements ProjectRule {
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("Controller", "RestController");
    private static final Set<String> SERVICE_ANNOTATIONS = Set.of("Service");
    private static final Set<String> REPOSITORY_ANNOTATIONS = Set.of("Repository");

    /**
     * Слои сверху вниз: зависеть можно только от того, что ниже
     */
    private enum Layer {
        CONTROLLER("контроллер"), SERVICE("сервис"), REPOSITORY("репозиторий");

        private final String title;

        Layer(String title) {
            this.title = title;
        }
    }

    @Override
    public String code() {
        return RuleCodes.LAYER_VIOLATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет зависимости против слоев: сервис от контроллера, репозиторий от сервиса";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // Имя класса -> слой; одноименные классы из разных слоев не учитываем
        Map<String, Optional<Layer>> layers = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                layerOf(type).ifPresent(layer -> layers.merge(type.getNameAsString(), Optional.of(layer),
                        (known, added) -> known.equals(added) ? known : Optional.empty()));
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<Layer> layer = layerOf(type);
                if (layer.isEmpty() || TestClasses.isInside(type)) {
                    continue;
                }
                for (SpringBeans.Dependency dependency : SpringBeans.findDependencies(type)) {
                    Optional<Layer> target = layers.getOrDefault(dependency.type(), Optional.empty());
                    if (target.isPresent() && target.get().ordinal() < layer.get().ordinal()) {
                        violations.add(violation(sourceFile, type,
                                "'" + type.getNameAsString() + "' (" + layer.get().title + ") зависит от '"
                                        + dependency.type() + "' (" + target.get().title + "): нижний слой не должен"
                                        + " знать о верхнем; вынесите общую логику в нижний слой"));
                    }
                }
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

    private Optional<Layer> layerOf(ClassOrInterfaceDeclaration type) {
        if (Annotations.hasAny(type, CONTROLLER_ANNOTATIONS)) {
            return Optional.of(Layer.CONTROLLER);
        }
        if (Annotations.hasAny(type, SERVICE_ANNOTATIONS)) {
            return Optional.of(Layer.SERVICE);
        }
        return Annotations.hasAny(type, REPOSITORY_ANNOTATIONS) ? Optional.of(Layer.REPOSITORY) : Optional.empty();
    }
}
