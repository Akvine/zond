package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
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
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class EntityColumnMismatchRule extends AbstractContextRule {
    private static final String NULLABLE = "nullable";
    private static final String LENGTH = "length";
    private static final String FALSE = "false";

    @Override
    public String code() {
        return RuleCodes.ENTITY_COLUMN_MISMATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет сущности JPA с миграциями и ищет расхождения в обязательности и длине колонок";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        DbSchema schema = DbSchema.of(context);
        List<Violation> violations = new ArrayList<>();
        if (schema.isEmpty()) {
            return violations;
        }
        ProjectClasses classes = ProjectClasses.of(context.sources());
        for (SourceFile sourceFile : context.sources()) {
            for (ClassOrInterfaceDeclaration entity : JpaEntities.findEntities(sourceFile.unit())) {
                Optional<DbSchema.Table> table = EntityMapping.tableName(entity, classes)
                        .flatMap(schema::find)
                        .filter(found -> !found.isOpaque());
                if (table.isEmpty()) {
                    continue;
                }
                for (EntityMapping.MappedField field : EntityMapping.fields(entity, classes)) {
                    Optional<DbSchema.Column> column = table.get().find(field.column());
                    if (column.isEmpty() || field.mapping().isEmpty()) {
                        continue;
                    }
                    String place = "'" + entity.getNameAsString() + "." + field.variable().getNameAsString() + "'";
                    for (String problem : compare(field.mapping().get(), column.get(), table.get())) {
                        violations.add(violation(sourceFile, field.variable(), "Поле " + place + ": " + problem));
                    }
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private List<String> compare(AnnotationExpr mapping, DbSchema.Column column, DbSchema.Table table) {
        List<String> problems = new ArrayList<>();
        String name = "'" + table.name() + "." + column.name() + "'";

        boolean required = Queries.member(mapping, NULLABLE).filter(value -> FALSE.equals(value.toString())).isPresent();
        if (required && !column.notNull()) {
            problems.add("в коде колонка объявлена обязательной (nullable = false), а в базе " + name
                    + " допускает NULL: строки, записанные в обход приложения, придут с пустым значением;"
                    + " добавьте NOT NULL миграцией");
        }

        Optional<Integer> length = Queries.member(mapping, LENGTH)
                .filter(value -> value.isIntegerLiteralExpr())
                .map(value -> value.asIntegerLiteralExpr().asNumber().intValue());
        if (length.isPresent() && column.length() != null && !length.get().equals(column.length())) {
            problems.add(length.get() > column.length()
                    ? "в коде длина " + length.get() + ", а в базе " + name + " вмещает только " + column.length()
                    + " символов: проверка длины в приложении пропустит строку, которую база отвергнет"
                    : "в коде длина " + length.get() + ", а в базе " + name + " вмещает " + column.length()
                    + " символов: объявления разошлись, приведите их к одному значению");
        }
        return problems;
    }
}
