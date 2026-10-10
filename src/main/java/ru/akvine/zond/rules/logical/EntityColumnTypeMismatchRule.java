package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Тип поля сущности и тип колонки из разных семейств: число против строки, дата против числа. Сверяются
 * только семейства - длина и точность здесь ни при чем, ими занимаются другие правила.
 */
@Component
public class EntityColumnTypeMismatchRule extends AbstractContextRule {
    private enum Family {
        TEXT("строка"), NUMBER("число"), BOOLEAN("логическое значение"), DATE_TIME("дата или время"),
        BINARY("двоичные данные"), UUID("идентификатор UUID"), ENUM_NAME("имя константы перечисления");

        private final String title;

        Family(String title) {
            this.title = title;
        }
    }

    private static final Set<String> TEXT_TYPES = Set.of("String", "char", "Character");
    private static final Set<String> NUMBER_TYPES = Set.of(
            "byte", "short", "int", "long", "float", "double", "Byte", "Short", "Integer", "Long", "Float", "Double",
            "BigDecimal", "BigInteger");
    private static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "Boolean");
    private static final Set<String> DATE_TIME_TYPES = Set.of(
            "LocalDate", "LocalDateTime", "LocalTime", "Instant", "OffsetDateTime", "ZonedDateTime", "OffsetTime",
            "Date", "Timestamp", "Calendar", "Time");
    private static final Set<String> BINARY_TYPES = Set.of("byte[]", "Byte[]", "Blob");
    private static final String UUID_TYPE = "UUID";

    private static final Set<String> SQL_TEXT = Set.of(
            "char", "character", "nchar", "varchar", "nvarchar", "varchar2", "nvarchar2", "character varying",
            "text", "tinytext", "mediumtext", "longtext", "clob", "nclob", "citext", "string");
    private static final Set<String> SQL_NUMBER = Set.of(
            "int", "int2", "int4", "int8", "integer", "bigint", "smallint", "tinyint", "mediumint", "serial",
            "bigserial", "smallserial", "numeric", "decimal", "dec", "number", "float", "float4", "float8", "real",
            "double", "double precision", "money", "smallmoney");
    private static final Set<String> SQL_BOOLEAN = Set.of("boolean", "bool", "bit");
    private static final Set<String> SQL_DATE_TIME = Set.of(
            "date", "datetime", "datetime2", "smalldatetime", "datetimeoffset", "timetz", "timestamptz");
    private static final List<String> SQL_TIME_PREFIXES = List.of("timestamp", "time");
    private static final Set<String> SQL_BINARY = Set.of(
            "bytea", "blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary", "raw", "long raw", "image");
    private static final Set<String> SQL_UUID = Set.of("uuid", "uniqueidentifier");
    private static final String SQL_ARRAY = "[]";

    // Что в какой колонке хранить можно. Логическое значение держат и в числовой колонке, UUID - и в строке,
    // и в двоичном виде; строку в колонку uuid драйвер передает без преобразования не везде, но так делают
    private static final Map<Family, Set<Family>> COMPATIBLE = Map.of(
            Family.TEXT, Set.of(Family.TEXT, Family.UUID),
            Family.NUMBER, Set.of(Family.NUMBER),
            Family.BOOLEAN, Set.of(Family.BOOLEAN, Family.NUMBER),
            Family.DATE_TIME, Set.of(Family.DATE_TIME),
            Family.BINARY, Set.of(Family.BINARY, Family.UUID),
            Family.UUID, Set.of(Family.UUID, Family.TEXT, Family.BINARY),
            Family.ENUM_NAME, Set.of(Family.TEXT));

    // Свое преобразование значения в колонку: сверять типы напрямую нельзя
    private static final Set<String> CUSTOM_MAPPING = Set.of(
            "Convert", "Type", "JdbcTypeCode", "JdbcType", "ColumnTransformer", "Formula", "Generated", "Lob");
    private static final Set<String> TO_ONE = Set.of("ManyToOne", "OneToOne");
    private static final String COLUMN_DEFINITION = "columnDefinition";
    private static final String ENUMERATED = "Enumerated";
    private static final String STRING = "STRING";
    private static final String CONVERTER = "Converter";
    private static final String AUTO_APPLY = "autoApply";
    private static final String ATTRIBUTE_CONVERTER = "AttributeConverter";
    private static final int FLAG_LENGTH = 1;

    private static final Map<String, Family> SQL_FAMILIES = new HashMap<>();

    static {
        SQL_TEXT.forEach(type -> SQL_FAMILIES.put(type, Family.TEXT));
        SQL_NUMBER.forEach(type -> SQL_FAMILIES.put(type, Family.NUMBER));
        SQL_BOOLEAN.forEach(type -> SQL_FAMILIES.put(type, Family.BOOLEAN));
        SQL_DATE_TIME.forEach(type -> SQL_FAMILIES.put(type, Family.DATE_TIME));
        SQL_BINARY.forEach(type -> SQL_FAMILIES.put(type, Family.BINARY));
        SQL_UUID.forEach(type -> SQL_FAMILIES.put(type, Family.UUID));
    }

    private static final Map<String, Family> JAVA_FAMILIES = new HashMap<>();

    static {
        TEXT_TYPES.forEach(type -> JAVA_FAMILIES.put(type, Family.TEXT));
        NUMBER_TYPES.forEach(type -> JAVA_FAMILIES.put(type, Family.NUMBER));
        BOOLEAN_TYPES.forEach(type -> JAVA_FAMILIES.put(type, Family.BOOLEAN));
        DATE_TIME_TYPES.forEach(type -> JAVA_FAMILIES.put(type, Family.DATE_TIME));
        BINARY_TYPES.forEach(type -> JAVA_FAMILIES.put(type, Family.BINARY));
        JAVA_FAMILIES.put(UUID_TYPE, Family.UUID);
    }

    /**
     * Что о типах известно из самого проекта
     *
     * @param enums     имена перечислений
     * @param converted типы, которые преобразует @Converter(autoApply = true): сверять их с колонкой напрямую нельзя
     */
    private record Known(Set<String> enums, Set<String> converted) {
    }

    @Override
    public String code() {
        return RuleCodes.ENTITY_COLUMN_TYPE_MISMATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет сущности со схемой БД и ищет поля, тип которых не подходит к типу колонки: число и строка, дата и число";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        DbSchema schema = DbSchema.of(context);
        List<Violation> violations = new ArrayList<>();
        if (schema.isEmpty()) {
            return violations;
        }
        ProjectClasses classes = ProjectClasses.of(context.sources());
        Known known = new Known(new HashSet<>(), new HashSet<>());
        for (SourceFile sourceFile : context.sources()) {
            sourceFile.unit().findAll(EnumDeclaration.class).forEach(found -> known.enums().add(found.getNameAsString()));
            sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)
                    .forEach(type -> known.converted().addAll(convertedBy(type)));
        }
        for (SourceFile sourceFile : context.sources()) {
            for (ClassOrInterfaceDeclaration entity : JpaEntities.findEntities(sourceFile.unit())) {
                Optional<DbSchema.Table> table = EntityMapping.tableName(entity, classes)
                        .flatMap(schema::find)
                        .filter(found -> !found.isOpaque());
                if (table.isEmpty()) {
                    continue;
                }
                String owner = entity.getNameAsString();
                for (EntityMapping.MappedField field : EntityMapping.fields(entity, classes)) {
                    describe(field, table.get(), known).ifPresent(problem -> violations.add(violation(
                            sourceFile, field.variable(),
                            "Поле '" + owner + "." + field.variable().getNameAsString() + "' " + problem)));
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

    /**
     * @return продолжение фразы "Поле 'Order.code' ...", если тип поля к типу колонки не подходит
     */
    private Optional<String> describe(EntityMapping.MappedField field, DbSchema.Table table, Known known) {
        String javaType = field.variable().getType().asString();
        Optional<DbSchema.Column> column = table.find(field.column());
        // Колонка связи хранит ключ другой сущности: ее тип сверяют с типом того ключа, а не с типом поля
        boolean skipped = column.isEmpty() || Annotations.hasAny(field.field(), TO_ONE) || hasCustomMapping(field)
                || known.converted().contains(javaType);
        if (skipped) {
            return Optional.empty();
        }
        DbSchema.Column found = column.get();
        Optional<Family> inCode = javaFamily(field, known.enums());
        Optional<Family> inBase = sqlFamily(found.type());
        if (inCode.isEmpty() || inBase.isEmpty() || COMPATIBLE.get(inCode.get()).contains(inBase.get())) {
            return Optional.empty();
        }
        // Y / N в колонке из одного символа: старый, но рабочий способ хранить логическое значение
        boolean flag = inCode.get() == Family.BOOLEAN && inBase.get() == Family.TEXT
                && Integer.valueOf(FLAG_LENGTH).equals(found.length());
        return flag
                ? Optional.empty()
                : Optional.of("в коде - " + inCode.get().title + " (" + javaType + "), а колонка '" + table.name() + "."
                + found.name() + "' в базе - " + inBase.get().title + " (" + found.type() + "): при записи или чтении"
                + " значение придется преобразовывать, и на первом неподходящем значении запрос упадет; приведите"
                + " тип поля и колонки к одному виду либо задайте преобразование явно (@Convert)");
    }

    private Optional<Family> javaFamily(EntityMapping.MappedField field, Set<String> enums) {
        Family known = JAVA_FAMILIES.get(field.variable().getType().asString());
        if (known != null) {
            return Optional.of(known);
        }
        // Перечисление, хранимое номером, само по себе находка другого правила; здесь - только хранимое именем
        boolean byName = enums.contains(LocalTypes.typeName(field.variable().getType()))
                && Annotations.find(field.field(), ENUMERATED).filter(found -> found.toString().contains(STRING)).isPresent();
        return byName ? Optional.of(Family.ENUM_NAME) : Optional.empty();
    }

    // varchar(50) -> varchar, timestamp(6) with time zone -> timestamp; пусто для типа, о котором судить нельзя:
    // json, массив, свой тип базы
    private Optional<Family> sqlFamily(String type) {
        if (type.contains(SQL_ARRAY)) {
            return Optional.empty();
        }
        int bracket = type.indexOf('(');
        String base = (bracket < 0 ? type : type.substring(0, bracket)).trim();
        Family known = SQL_FAMILIES.get(base);
        if (known != null) {
            return Optional.of(known);
        }
        boolean dateTime = SQL_TIME_PREFIXES.stream().anyMatch(prefix -> base.equals(prefix) || base.startsWith(prefix + " "));
        return dateTime ? Optional.of(Family.DATE_TIME) : Optional.empty();
    }

    private boolean hasCustomMapping(EntityMapping.MappedField field) {
        return Annotations.hasAny(field.field(), CUSTOM_MAPPING)
                || field.mapping().flatMap(mapping -> Queries.member(mapping, COLUMN_DEFINITION)).isPresent();
    }

    // @Converter(autoApply = true) class MoneyConverter implements AttributeConverter<Money, BigDecimal>:
    // поля типа Money преобразуются сами, без @Convert на каждом
    private Set<String> convertedBy(ClassOrInterfaceDeclaration type) {
        Set<String> types = new HashSet<>();
        boolean autoApply = Annotations.find(type, CONVERTER)
                .filter(annotation -> annotation.toString().contains(AUTO_APPLY))
                .isPresent();
        if (!autoApply) {
            return types;
        }
        for (ClassOrInterfaceType implemented : type.getImplementedTypes()) {
            if (ATTRIBUTE_CONVERTER.equals(implemented.getNameAsString())) {
                implemented.getTypeArguments()
                        .filter(arguments -> !arguments.isEmpty())
                        .ifPresent(arguments -> types.add(arguments.get(0).asString()));
            }
        }
        return types;
    }
}
