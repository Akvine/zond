package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import ru.akvine.zond.rules.support.DerivedQueries;
import ru.akvine.zond.rules.support.EntityMapping;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;
import ru.akvine.zond.rules.support.RepositoryEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Запрос по колонке, на которой нет индекса. Запрос берется из имени метода репозитория Spring Data,
 * индексы - из миграций: и те, и другие известны точно, а вот велика ли таблица, по коду не узнать.
 */
@Component
public class QueryWithoutIndexRule extends AbstractContextRule {
    // Условия, которым индекс по колонке помогает: равенство, список значений, диапазон, начало строки
    private static final Set<DerivedQueries.Operator> INDEXABLE = Set.of(
            DerivedQueries.Operator.EQUALS, DerivedQueries.Operator.IN, DerivedQueries.Operator.RANGE,
            DerivedQueries.Operator.PREFIX);
    private static final Set<String> KEY_ANNOTATIONS = Set.of("Id", "EmbeddedId");
    // Значений всего два: индекс по такой колонке базе почти не помогает
    private static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "Boolean");

    @Override
    public String code() {
        return RuleCodes.QUERY_WITHOUT_INDEX_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет методы репозиториев со схемой БД и ищет запросы по колонкам, на которых нет индекса";
    }

    // Отсутствие индекса видно точно, но мешает оно только на большой таблице - а ее размер неизвестен
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
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
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<ClassOrInterfaceDeclaration> entity = Queries.isRepositoryInterface(type)
                        ? RepositoryEntities.entityOf(type, classes)
                        : Optional.empty();
                if (entity.isEmpty()) {
                    continue;
                }
                Optional<DbSchema.Table> table = EntityMapping.tableName(entity.get(), classes)
                        .flatMap(schema::find)
                        .filter(found -> !found.isOpaque());
                List<EntityMapping.MappedField> fields = EntityMapping.fields(entity.get(), classes);
                for (MethodDeclaration method : type.getMethods()) {
                    table.flatMap(found -> describe(method, fields, found)).ifPresent(problem ->
                            violations.add(violation(sourceFile, method.getName(), problem)));
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
        return ErrorType.PERFORMANCE;
    }

    /**
     * @return текст находки, если метод ищет по колонкам, ни с одной из которых не начинается ни один индекс
     */
    private Optional<String> describe(MethodDeclaration method, List<EntityMapping.MappedField> fields, DbSchema.Table table) {
        // Запрос, написанный вручную, или метод с собственным кодом из имени не читается.
        // Условия через Or: каждому нужен свой индекс, и одной колонкой запрос не описать
        Optional<List<String>> columns = DerivedQueries.parse(method.getNameAsString())
                .filter(query -> Queries.find(method).isEmpty() && method.getBody().isEmpty() && !query.alternatives())
                .flatMap(query -> searchedColumns(query, fields, table));
        if (columns.isEmpty() || columns.get().isEmpty() || table.hasIndexOn(columns.get())) {
            return Optional.empty();
        }
        List<String> names = columns.get();
        String where = names.size() == 1
                ? "по колонке '" + names.get(0) + "', а индекса, который начинается с нее,"
                : "по колонкам " + String.join(", ", names) + ", а индекса, который начинается с одной из них,";
        return Optional.of("Метод '" + method.getNameAsString() + "' ищет " + where + " в таблице '" + table.name()
                + "' нет: база просматривает таблицу целиком, и с ростом числа строк запрос будет замедляться;"
                + " добавьте индекс, если таблица большая или метод вызывается часто");
    }

    /**
     * @return колонки, поиску по которым помог бы индекс; пусто, если судить о запросе нельзя либо индекс
     * ему не нужен
     */
    private Optional<List<String>> searchedColumns(
            DerivedQueries.Query query, List<EntityMapping.MappedField> fields, DbSchema.Table table) {
        List<String> columns = new ArrayList<>();
        for (DerivedQueries.Part part : query.parts()) {
            Optional<EntityMapping.MappedField> field = DerivedQueries.fieldOf(fields, part.property());
            // Свойство вложенное или унаследованное: в какой колонке оно лежит, неизвестно. Поиск по ключу
            // обслуживает сам первичный ключ
            if (field.isEmpty() || Annotations.hasAny(field.get().field(), KEY_ANNOTATIONS)) {
                return Optional.empty();
            }
            boolean usable = INDEXABLE.contains(part.operator()) && !part.ignoreCase()
                    && !BOOLEAN_TYPES.contains(LocalTypes.typeName(field.get().variable().getType()));
            if (usable) {
                // Имя берется таким, как оно записано в миграциях, а не в @Column
                String declared = field.get().column();
                columns.add(table.find(declared).map(DbSchema.Column::name).orElse(declared));
            }
        }
        return Optional.of(columns);
    }
}
