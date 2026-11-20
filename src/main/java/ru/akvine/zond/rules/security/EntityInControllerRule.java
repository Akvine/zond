package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.JpaEntities;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class EntityInControllerRule extends AbstractRule implements ProjectRule {
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("Controller", "RestController");
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");

    @Override
    public String code() {
        return RuleCodes.ENTITY_IN_CONTROLLER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет JPA-сущности, которые контроллер возвращает или принимает как тело запроса";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!Annotations.hasAny(type, CONTROLLER_ANNOTATIONS)) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    if (Annotations.hasAny(method, MAPPING_ANNOTATIONS)) {
                        checkEndpoint(sourceFile, method, classes, violations);
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
        return ErrorType.SECURITY;
    }

    // Сущность в теле запроса - отдельное правило jr:238: здесь только то, что уходит наружу
    private void checkEndpoint(
            SourceFile sourceFile, MethodDeclaration method, ProjectClasses classes, List<Violation> violations) {
        JpaEntities.entityIn(method.getType(), classes).ifPresent(entity -> violations.add(violation(sourceFile, method,
                "Метод контроллера '" + method.getNameAsString() + "' возвращает сущность '"
                        + entity.getNameAsString() + "': наружу уйдут все ее поля, включая служебные, а сериализация"
                        + " LAZY-связей даст лишние запросы или LazyInitializationException; возвращайте DTO")));
    }
}
