package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.DbSchema;
import ru.akvine.zond.rules.support.EntityMapping;
import ru.akvine.zond.rules.support.JpaEntities;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class EntityFieldWithoutColumnRule extends AbstractContextRule {
    @Override
    public String code() {
        return RuleCodes.ENTITY_FIELD_WITHOUT_COLUMN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет сущности JPA с миграциями и ищет поля, для которых в таблице нет колонки";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        DbSchema schema = DbSchema.of(context);
        List<Violation> violations = new ArrayList<>();
        // Без миграций сверять не с чем: схему может создавать Hibernate или другой проект
        if (schema.isEmpty()) {
            return violations;
        }
        ProjectClasses classes = ProjectClasses.of(context.sources());
        for (SourceFile sourceFile : context.sources()) {
            for (ClassOrInterfaceDeclaration entity : JpaEntities.findEntities(sourceFile.unit())) {
                // Таблицы нет в миграциях - значит, она создается не здесь: о ее колонках ничего не известно
                Optional<DbSchema.Table> table = EntityMapping.tableName(entity, classes)
                        .flatMap(schema::find)
                        .filter(found -> !found.isOpaque());
                if (table.isEmpty()) {
                    continue;
                }
                for (EntityMapping.MappedField field : EntityMapping.fields(entity, classes)) {
                    if (table.get().find(field.column()).isEmpty()) {
                        violations.add(violation(sourceFile, field.variable(),
                                "Поле '" + entity.getNameAsString() + "." + field.variable().getNameAsString()
                                        + "' хранится в колонке '" + field.column() + "', а в таблице '"
                                        + table.get().name() + "' по миграциям такой колонки нет: запрос к сущности"
                                        + " упадет при выполнении; добавьте миграцию либо исправьте имя колонки"));
                    }
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
