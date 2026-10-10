package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class EnumLongerThanColumnRule extends AbstractContextRule {
    private static final String ENUMERATED = "Enumerated";
    private static final String STRING = "STRING";
    private static final String LENGTH = "length";

    @Override
    public String code() {
        return RuleCodes.ENUM_LONGER_THAN_COLUMN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет сущности со схемой БД и ищет перечисления, имена констант которых длиннее колонки";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        DbSchema schema = DbSchema.of(context);
        ProjectClasses classes = ProjectClasses.of(context.sources());
        Map<String, List<EnumDeclaration>> enums = enumsOf(context.sources());
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            for (ClassOrInterfaceDeclaration entity : JpaEntities.findEntities(sourceFile.unit())) {
                Optional<DbSchema.Table> table = EntityMapping.tableName(entity, classes)
                        .flatMap(schema::find)
                        .filter(found -> !found.isOpaque());
                for (EntityMapping.MappedField field : EntityMapping.fields(entity, classes)) {
                    check(sourceFile, entity, field, table, enums, violations);
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
        return ErrorType.LOGICAL;
    }

    private void check(
            SourceFile sourceFile, ClassOrInterfaceDeclaration entity, EntityMapping.MappedField field,
            Optional<DbSchema.Table> table, Map<String, List<EnumDeclaration>> enums, List<Violation> violations) {
        // Без STRING в колонке лежит номер константы, и длина имен ни на что не влияет
        boolean asText = Annotations.find(field.field(), ENUMERATED)
                .filter(annotation -> annotation.toString().contains(STRING))
                .isPresent();
        List<EnumDeclaration> declared = enums.getOrDefault(LocalTypes.typeName(field.variable().getType()), List.of());
        if (!asText || declared.size() != 1) {
            return;
        }
        Optional<String> longest = declared.get(0).getEntries().stream()
                .map(EnumConstantDeclaration::getNameAsString)
                .max(Comparator.comparingInt(String::length));
        if (longest.isEmpty()) {
            return;
        }

        // Длина из миграций - то, что есть в базе на самом деле; длина из @Column - то, что о ней сказано в коде
        Optional<DbSchema.Column> column = table.flatMap(found -> found.find(field.column()));
        Optional<Integer> inSchema = column.map(DbSchema.Column::length);
        Optional<Integer> inCode = field.mapping().flatMap(this::lengthOf);
        if (inSchema.isEmpty() && inCode.isEmpty()) {
            return;
        }
        int limit = inSchema.orElseGet(inCode::get);
        if (longest.get().length() <= limit) {
            return;
        }
        String place = inSchema.isPresent()
                ? "колонка '" + table.get().name() + "." + column.get().name() + "' вмещает только " + limit
                : "по @Column(length = " + limit + ") колонка вмещает только " + limit;
        violations.add(violation(sourceFile, field.variable(),
                "Поле '" + entity.getNameAsString() + "." + field.variable().getNameAsString() + "': константа "
                        + declared.get(0).getNameAsString() + "." + longest.get() + " занимает "
                        + longest.get().length() + " символов, а " + place + " - запись этого значения закончится"
                        + " ошибкой базы; увеличьте длину колонки")
                .withConfidence(inSchema.isPresent() ? Confidence.CONFIRMED : Confidence.PROBABLE));
    }

    private Optional<Integer> lengthOf(AnnotationExpr mapping) {
        return Queries.member(mapping, LENGTH)
                .filter(value -> value.isIntegerLiteralExpr())
                .map(value -> value.asIntegerLiteralExpr().asNumber().intValue());
    }

    // Перечисление ищется по простому имени: два одноименных в проекте - неизвестно, какое из них в поле
    private Map<String, List<EnumDeclaration>> enumsOf(List<SourceFile> sources) {
        Map<String, List<EnumDeclaration>> enums = new HashMap<>();
        for (SourceFile source : sources) {
            for (EnumDeclaration declaration : source.unit().findAll(EnumDeclaration.class)) {
                enums.computeIfAbsent(declaration.getNameAsString(), name -> new ArrayList<>()).add(declaration);
            }
        }
        return enums;
    }
}
