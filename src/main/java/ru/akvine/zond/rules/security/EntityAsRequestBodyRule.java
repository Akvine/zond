package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.Parameter;
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
public class EntityAsRequestBodyRule extends AbstractRule implements ProjectRule {
    private static final Set<String> BODY_ANNOTATIONS = Set.of("RequestBody", "ModelAttribute");

    @Override
    public String code() {
        return RuleCodes.ENTITY_AS_REQUEST_BODY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет JPA-сущности, которые контроллер принимает в теле запроса";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (Parameter parameter : sourceFile.findAll(Parameter.class)) {
                if (!Annotations.hasAny(parameter, BODY_ANNOTATIONS)) {
                    continue;
                }
                // Сама сущность либо список сущностей: @RequestBody List<Order> orders
                JpaEntities.entityIn(parameter.getType(), classes).ifPresent(entity -> violations.add(violation(
                        sourceFile, parameter,
                        "Сущность '" + entity.getNameAsString() + "' принимается в теле запроса: клиент может задать"
                                + " любое ее поле, включая id, роль и владельца (mass assignment); принимайте DTO"
                                + " только с теми полями, которые разрешено менять")));
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
}
