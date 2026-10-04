package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
import ru.akvine.zond.rules.support.JpaEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CheckEntityInControllerRule extends AbstractRule implements ProjectRule {
    private static final String REQUEST_BODY = "RequestBody";
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("Controller", "RestController");
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");

    @Override
    public String code() {
        return RuleCodes.CHECK_ENTITY_IN_CONTROLLER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет JPA-сущности, которые контроллер возвращает или принимает как тело запроса";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // Какие классы являются сущностями, видно только по всему проекту: они объявлены в других файлах
        Set<String> entities = sourceFiles.stream()
                .flatMap(sourceFile -> JpaEntities.findEntities(sourceFile.unit()).stream())
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .collect(Collectors.toSet());
        if (entities.isEmpty()) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!Annotations.hasAny(type, CONTROLLER_ANNOTATIONS)) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    if (Annotations.hasAny(method, MAPPING_ANNOTATIONS)) {
                        checkEndpoint(sourceFile, method, entities, violations);
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

    private void checkEndpoint(
            SourceFile sourceFile, MethodDeclaration method, Set<String> entities, List<Violation> violations) {
        findEntity(method.getType(), entities).ifPresent(entity -> violations.add(violation(sourceFile, method,
                "Метод контроллера '" + method.getNameAsString() + "' возвращает сущность '" + entity + "': наружу"
                        + " уйдут все ее поля, включая служебные, а сериализация LAZY-связей даст лишние запросы"
                        + " или LazyInitializationException; возвращайте DTO")));

        for (Parameter parameter : method.getParameters()) {
            if (!Annotations.has(parameter, REQUEST_BODY)) {
                continue;
            }
            findEntity(parameter.getType(), entities).ifPresent(entity -> violations.add(violation(
                    sourceFile, parameter,
                    "Метод контроллера '" + method.getNameAsString() + "' принимает сущность '" + entity + "' как"
                            + " тело запроса: клиент сможет задать любые ее поля, в том числе идентификатор и"
                            + " служебные (mass assignment); принимайте DTO")));
        }
    }

    // Сама сущность либо обертка над ней: List<Order>, ResponseEntity<Page<Order>>
    private Optional<String> findEntity(Type type, Set<String> entities) {
        return type.findAll(ClassOrInterfaceType.class).stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .filter(entities::contains)
                .findFirst();
    }
}
