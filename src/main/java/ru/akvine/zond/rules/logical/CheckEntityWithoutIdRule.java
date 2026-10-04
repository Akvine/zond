package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.JpaEntities;

import java.util.List;
import java.util.Set;

@Component
public class CheckEntityWithoutIdRule extends AbstractRule {
    private static final Set<String> ID_ANNOTATIONS = Set.of("Id", "EmbeddedId");

    // @IdClass на классе: идентификатор составной и описан отдельным классом
    private static final String ID_CLASS = "IdClass";

    @Override
    public String code() {
        return RuleCodes.CHECK_ENTITY_WITHOUT_ID_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сущности без идентификатора";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // У сущности с родителем @Id может быть объявлен в нем
        return JpaEntities.findEntities(sourceFile.unit()).stream()
                .filter(entity -> !JpaEntities.hasParent(entity))
                .filter(entity -> !hasId(entity))
                .map(entity -> violation(sourceFile, entity,
                        "Сущность '" + entity.getNameAsString() + "' без @Id: JPA требует идентификатор у каждой"
                                + " сущности, приложение не стартует; добавьте поле с @Id или @EmbeddedId"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean hasId(ClassOrInterfaceDeclaration entity) {
        return Annotations.has(entity, ID_CLASS)
                || entity.getFields().stream().anyMatch(field -> Annotations.hasAny(field, ID_ANNOTATIONS))
                || entity.getMethods().stream().anyMatch(method -> Annotations.hasAny(method, ID_ANNOTATIONS));
    }
}
