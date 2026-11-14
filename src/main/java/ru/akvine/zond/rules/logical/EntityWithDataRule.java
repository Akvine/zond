package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
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
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

@Component
public class EntityWithDataRule extends AbstractRule {
    private static final String DATA = "Data";
    private static final Set<String> RELATIONS = Set.of(
            "OneToMany", "ManyToOne", "OneToOne", "ManyToMany", "ElementCollection", "Any", "ManyToAny");

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
                .map(entity -> hasRelations(entity)
                        ? violation(sourceFile, entity,
                        "@Data на сущности '" + entity.getNameAsString() + "': Lombok сгенерирует equals / hashCode"
                                + " / toString по всем полям, включая связи - это ленивые загрузки, рекурсия на"
                                + " двусторонних связях и hashCode, меняющийся после сохранения; используйте"
                                + " @Getter / @Setter и напишите equals / hashCode по идентификатору")
                        // Без связей нет ни ленивых загрузок, ни рекурсии: остается только hashCode по изменяемым
                        // полям, а он мешает, лишь если сущность кладут в HashSet или в ключи HashMap
                        : violation(sourceFile, entity,
                        "@Data на сущности '" + entity.getNameAsString() + "': связей у нее нет, поэтому ленивых"
                                + " загрузок и рекурсии не будет, но hashCode считается по всем полям и меняется"
                                + " после сохранения и любого изменения - сущность потеряется в HashSet и в ключах"
                                + " HashMap; используйте @Getter / @Setter и equals / hashCode по идентификатору")
                        .withConfidence(Confidence.SUSPICION))
                .toList();
    }

    // Связь объявлена в поле либо на геттере
    private boolean hasRelations(ClassOrInterfaceDeclaration entity) {
        return entity.getFields().stream().anyMatch(field -> Annotations.hasAny(field, RELATIONS))
                || entity.getMethods().stream().anyMatch(method -> Annotations.hasAny(method, RELATIONS));
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
