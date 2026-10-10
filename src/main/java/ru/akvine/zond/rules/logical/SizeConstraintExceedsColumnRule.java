package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.DbSchema;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.EntityMapping;
import ru.akvine.zond.rules.support.JpaEntities;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class SizeConstraintExceedsColumnRule extends AbstractContextRule {
    // jakarta / javax @Size и @Length из Hibernate Validator
    private static final Set<String> SIZE_ANNOTATIONS = Set.of("Size", "Length");
    private static final String MAX = "max";
    private static final String LENGTH = "length";

    @Override
    public String code() {
        return RuleCodes.SIZE_CONSTRAINT_EXCEEDS_COLUMN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет сущности со схемой БД и ищет проверку длины строки, которая допускает больше, чем вмещает колонка";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        DbSchema schema = DbSchema.of(context);
        ProjectClasses classes = ProjectClasses.of(context.sources());
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            for (ClassOrInterfaceDeclaration entity : JpaEntities.findEntities(sourceFile.unit())) {
                Optional<DbSchema.Table> table = EntityMapping.tableName(entity, classes)
                        .flatMap(schema::find)
                        .filter(found -> !found.isOpaque());
                for (EntityMapping.MappedField field : EntityMapping.fields(entity, classes)) {
                    check(sourceFile, entity, field, table, violations);
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

    private void check(
            SourceFile sourceFile, ClassOrInterfaceDeclaration entity, EntityMapping.MappedField field,
            Optional<DbSchema.Table> table, List<Violation> violations) {
        Optional<Integer> allowed = Annotations.find(field.field(), SIZE_ANNOTATIONS).flatMap(size -> number(size, MAX));
        if (allowed.isEmpty()) {
            return;
        }
        Optional<DbSchema.Column> column = table.flatMap(found -> found.find(field.column()));
        Optional<Integer> inSchema = column.map(DbSchema.Column::length);
        Optional<Integer> inCode = field.mapping().flatMap(mapping -> number(mapping, LENGTH));
        if (inSchema.isEmpty() && inCode.isEmpty()) {
            return;
        }
        int limit = inSchema.orElseGet(inCode::get);
        if (allowed.get() <= limit) {
            return;
        }
        String place = inSchema.isPresent()
                ? "колонка '" + table.get().name() + "." + column.get().name() + "' вмещает только " + limit
                : "по @Column(length = " + limit + ") колонка вмещает только " + limit;
        violations.add(violation(sourceFile, field.variable(),
                "Поле '" + entity.getNameAsString() + "." + field.variable().getNameAsString() + "': проверка"
                        + " допускает строку до " + allowed.get() + " символов, а " + place + " - значение,"
                        + " которое прошло проверку, база отвергнет с ошибкой; приведите ограничение"
                        + " и длину колонки к одному числу")
                .withConfidence(inSchema.isPresent() ? Confidence.CONFIRMED : Confidence.PROBABLE));
    }

    private Optional<Integer> number(AnnotationExpr annotation, String member) {
        return Queries.member(annotation, member)
                .filter(value -> value.isIntegerLiteralExpr())
                .map(value -> value.asIntegerLiteralExpr().asNumber().intValue());
    }
}
