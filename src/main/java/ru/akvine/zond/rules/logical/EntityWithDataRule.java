package ru.akvine.zond.rules.logical;

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
public class EntityWithDataRule extends AbstractRule {
    private static final String DATA = "Data";

    @Override
    public String code() {
        return RuleCodes.ENTITY_WITH_DATA_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет JPA-сущности с аннотацией Lombok @Data";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return JpaEntities.findEntities(sourceFile.unit()).stream()
                .filter(entity -> Annotations.has(entity, DATA))
                .map(entity -> violation(sourceFile, entity,
                        "@Data на сущности '" + entity.getNameAsString() + "': Lombok сгенерирует equals / hashCode"
                                + " / toString по всем полям, включая связи - это ленивые загрузки, рекурсия на"
                                + " двусторонних связях и hashCode, меняющийся после сохранения; используйте"
                                + " @Getter / @Setter и напишите equals / hashCode по идентификатору"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
