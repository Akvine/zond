package ru.akvine.zond.rules.concurrency;

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

@Component
public class EntityWithoutVersionRule extends AbstractRule {
    private static final String VERSION = "Version";

    @Override
    public String code() {
        return RuleCodes.ENTITY_WITHOUT_VERSION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сущности без поля @Version";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // У сущности с родителем @Version может быть объявлен в нем
        return JpaEntities.findEntities(sourceFile.unit()).stream()
                .filter(entity -> !JpaEntities.hasParent(entity))
                .filter(entity -> !hasVersion(entity))
                .map(entity -> violation(sourceFile, entity,
                        "Сущность '" + entity.getNameAsString() + "' без @Version: при одновременном изменении"
                                + " побеждает последняя запись, изменения первой теряются без ошибки;"
                                + " добавьте поле с @Version для оптимистичной блокировки"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private boolean hasVersion(ClassOrInterfaceDeclaration entity) {
        return entity.getFields().stream().anyMatch(field -> Annotations.has(field, VERSION))
                || entity.getMethods().stream().anyMatch(method -> Annotations.has(method, VERSION));
    }
}
