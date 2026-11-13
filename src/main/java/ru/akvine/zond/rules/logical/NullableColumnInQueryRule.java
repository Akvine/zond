package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import ru.akvine.zond.rules.support.JpaProperties;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;
import ru.akvine.zond.rules.support.QueryTexts;
import ru.akvine.zond.rules.support.RepositoryEntities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NULL в запросах. В SQL сравнение с NULL дает не true и не false, а UNKNOWN, и строка в результат не попадает:
 * {@code status <> 'DONE'} молча теряет строки, где status не заполнен, {@code NOT IN (подзапрос)} не возвращает
 * ничего, если подзапрос вернул хотя бы один NULL, {@code = NULL} не выполняется никогда, а SUM по пустой выборке
 * равен NULL, а не нулю. Допускает ли колонка NULL, правило узнает по миграциям, а если их нет - по описанию
 * сущности.
 */
@Component
public class NullableColumnInQueryRule extends AbstractContextRule {
    private static final String ENTITY = "Entity";
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    // from Order o, update Order o, delete from Order o, а также второй корень через запятую
    private static final Pattern ROOT = Pattern.compile(
            "(?:\\bfrom|\\bupdate|,)\\s+([A-Z]\\w*)\\s+(?:as\\s+)?([A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);
    // join o.items i, left join fetch o.customer c
    private static final Pattern JOIN = Pattern.compile(
            "\\bjoin\\s+(?:fetch\\s+)?([A-Za-z_]\\w*)\\.([\\w.]+)\\s+(?:as\\s+)?([A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> KEYWORDS = Set.of(
            "where", "join", "left", "right", "inner", "outer", "cross", "full", "on", "set", "order", "group",
            "having", "fetch", "union", "limit", "with", "as", "and", "or");

    private static final String PATH = "([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)";
    // o.status <> :status, o.status != 'DONE', o.status not in (...), o.name not like :mask
    private static final Pattern NEGATIVE = Pattern.compile(
            "(?<![:.\\w#])" + PATH + "\\s*(<>|!=|\\bnot\\s+in\\b|\\bnot\\s+like\\b)", Pattern.CASE_INSENSITIVE);
    // :status <> o.status
    private static final Pattern NEGATIVE_REVERSED = Pattern.compile(
            "(<>|!=)\\s*" + PATH + "\\b(?!\\s*\\()", Pattern.CASE_INSENSITIVE);
    // o.id not in (select b.orderId from Blocked b)
    private static final Pattern NOT_IN_SUBQUERY = Pattern.compile(
            "\\bnot\\s+in\\s*\\(\\s*select\\s+(?:distinct\\s+)?" + PATH + "\\s+from\\b", Pattern.CASE_INSENSITIVE);
    // status = NULL, status <> null: но не >= и не jdbcType=NULL из MyBatis
    private static final Pattern NULL_COMPARISON = Pattern.compile(
            "([\\w.]+)\\s*(?<![<>!=])(=|<>|!=)\\s*null\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAUSE = Pattern.compile("\\b(set|update|where|on|having|when)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ASSIGNMENT_CLAUSES = Set.of("set", "update");
    private static final String JDBC_TYPE = "jdbctype";
    // Динамический SQL MyBatis: в <if test="status != null"> сравнивают параметр в Java, а не колонку в базе
    private static final Pattern MYBATIS_TAG = Pattern.compile(
            "</?(?:if|when|where|set|foreach|choose|otherwise|trim|bind|script|select|update|delete|insert|include)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);

    // select sum(o.amount) from Order o ...: единственное значение - итог по всей выборке
    private static final Pattern AGGREGATE = Pattern.compile(
            "^\\s*select\\s+(sum|max|min|avg)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP_BY = Pattern.compile("\\bgroup\\s+by\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM = Pattern.compile("^\\s*(?:as\\s+\\w+\\s+)?from\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NULL_SAFE_RESULTS = Set.of("Optional", "OptionalLong", "OptionalInt", "OptionalDouble");

    // Имя метода Spring Data: findByStatusNot, countByTypeNotIn
    private static final Pattern DERIVED = Pattern.compile(
            "^(find|read|get|query|search|stream|count|exists|delete|remove)(?:\\p{Upper}\\w*?)?By(\\p{Upper}.*)$");
    private static final Pattern ORDER_BY = Pattern.compile("OrderBy\\p{Upper}.*$");
    private static final Pattern AND_OR = Pattern.compile("(?<=[a-z0-9])(?:And|Or)(?=\\p{Upper})");
    private static final List<String> IGNORE_CASE_SUFFIXES = List.of(
            "AllIgnoreCase", "AllIgnoringCase", "IgnoreCase", "IgnoringCase");
    // Длинное слово раньше короткого, которым оно заканчивается
    private static final List<String> NEGATIVE_SUFFIXES = List.of(
            "IsNotIn", "NotIn", "IsNotLike", "NotLike", "IsNotContaining", "NotContaining", "NotContains", "IsNot", "Not");
    private static final List<String> NULL_CHECK_SUFFIXES = List.of("IsNotNull", "NotNull", "IsNull", "Null");
    private static final Set<String> NULL_CHECKS = Set.copyOf(NULL_CHECK_SUFFIXES);

    // Поле, которое не бывает пустым по самому описанию
    private static final Set<String> NOT_NULL_ANNOTATIONS = Set.of(
            "Id", "EmbeddedId", "NotNull", "NonNull", "Nonnull", "NotBlank", "NotEmpty", "Version", "CreatedDate",
            "CreationTimestamp", "LastModifiedDate", "UpdateTimestamp", "CreatedBy", "TenantId");
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of("Column", "JoinColumn", "ManyToOne", "OneToOne", "Basic");
    private static final Pattern NOT_NULL_MEMBER = Pattern.compile("\\b(nullable|optional)\\s*=\\s*false\\b");
    // Коллекции: в самой таблице у них колонки нет
    private static final Set<String> COLLECTION_RELATIONS = Set.of("OneToMany", "ManyToMany", "ElementCollection");

    // Насколько точно известно, что в колонке бывает NULL
    private enum Nullability {
        /** Колонка NOT NULL либо о поле ничего не известно */
        NOT_NULL,
        /** В миграциях колонка объявлена без NOT NULL */
        BY_SCHEMA,
        /** Миграций нет: у поля сущности нет ни nullable = false, ни @NotNull */
        BY_MAPPING
    }

    // Все, что известно о проекте: классы и схема базы из миграций
    private record Project(ProjectClasses classes, DbSchema schema) {
    }

    // Метод репозитория, запрос которого проверяется
    private record Site(SourceFile sourceFile, MethodDeclaration method) {
        String name() {
            return method.getNameAsString();
        }
    }

    // Поле сущности вместе с классом, в котором оно объявлено
    private record Declared(ClassOrInterfaceDeclaration owner, FieldDeclaration field, String property) {
    }

    private record Column(String text, Nullability nullability) {
        Confidence confidence() {
            return nullability == Nullability.BY_SCHEMA ? Confidence.CONFIRMED : Confidence.PROBABLE;
        }

        String source() {
            return nullability == Nullability.BY_SCHEMA
                    ? "в миграциях колонка объявлена без NOT NULL"
                    : "у поля сущности нет ни nullable = false, ни @NotNull";
        }
    }

    @Override
    public String code() {
        return RuleCodes.NULLABLE_COLUMN_IN_QUERY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует запросы и ищет условия, которые молча теряют строки с NULL: <> и NOT IN по колонке,"
                + " допускающей NULL, сравнение = NULL, NOT IN с подзапросом и SUM / MAX без COALESCE";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Project project = new Project(ProjectClasses.of(context.sources()), DbSchema.of(context));
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<ClassOrInterfaceDeclaration> entity = Queries.isSpringDataRepository(type, project.classes())
                        ? RepositoryEntities.entityOf(type, project.classes())
                        : Optional.empty();
                for (MethodDeclaration method : type.getMethods()) {
                    Site site = new Site(sourceFile, method);
                    if (Queries.find(method).isPresent()) {
                        checkQuery(site, project, violations);
                    } else {
                        // Запрос строит Spring Data по имени метода
                        entity.ifPresent(found -> checkDerived(site, found, project, violations));
                    }
                }
            }
        }

        for (QueryTexts.QueryText query : QueryTexts.of(context)) {
            findNullComparison(query.text()).ifPresent(comparison -> violations.add(violation(query.file(), query.line(),
                    "'" + comparison + "': сравнение с NULL через = или <> дает не true и не false, а UNKNOWN - условие"
                            + " не выполняется ни для одной строки, и запрос молча ничего не находит; проверяйте"
                            + " через IS NULL / IS NOT NULL").withConfidence(Confidence.CONFIRMED)));
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

    // Запрос из @Query: условия проверяются только в JPQL, агрегаты и в нативном SQL
    private void checkQuery(Site site, Project project, List<Violation> violations) {
        Optional<AnnotationExpr> query = Queries.find(site.method());
        Optional<String> text = query.flatMap(Queries::text);
        boolean jpql = query.filter(annotation -> !Queries.isNative(annotation)).isPresent();
        if (text.isPresent() && jpql) {
            checkConditions(site, text.get(), project, violations);
        }
        text.ifPresent(found -> checkAggregate(site, found, violations));
    }

    // Условия JPQL: путь o.status разрешается в поле сущности, а по полю видно, бывает ли в колонке NULL
    private void checkConditions(Site site, String query, Project project, List<Violation> violations) {
        String text = SPACES.matcher(STRING_LITERAL.matcher(query).replaceAll("''")).replaceAll(" ");
        Map<String, ClassOrInterfaceDeclaration> aliases = aliasesOf(text, project.classes());
        Set<String> reported = new LinkedHashSet<>();

        Matcher subquery = NOT_IN_SUBQUERY.matcher(text);
        while (subquery.find()) {
            String path = subquery.group(1) + "." + subquery.group(2);
            String body = text.substring(subquery.start(), closingParenthesis(text, subquery.start()));
            Optional<Column> column = column(aliases.get(subquery.group(1)), subquery.group(2), project);
            if (column.isPresent() && !isNullChecked(body, path) && reported.add("subquery " + path)) {
                violations.add(violation(site.sourceFile(), site.method().getName(),
                        subject(site) + "подзапрос в NOT IN выбирает '" + path
                                + "', а в этой колонке бывает NULL (" + column.get().source() + "). Если подзапрос"
                                + " вернет хотя бы один NULL, условие NOT IN не выполнится ни для одной строки, и"
                                + " запрос молча вернет пустой результат; добавьте в подзапрос " + path
                                + " IS NOT NULL либо перепишите условие через NOT EXISTS")
                        .withConfidence(column.get().confidence()));
            }
        }

        for (Pattern pattern : List.of(NEGATIVE, NEGATIVE_REVERSED)) {
            Matcher negative = pattern.matcher(text);
            while (negative.find()) {
                boolean reversed = pattern == NEGATIVE_REVERSED;
                String alias = negative.group(reversed ? 2 : 1);
                String property = negative.group(reversed ? 3 : 2);
                // Пробелы в тексте уже сведены к одному: not in остается как есть
                String operator = negative.group(reversed ? 1 : 3).toUpperCase(Locale.ROOT);
                String path = alias + "." + property;
                Optional<Column> column = column(aliases.get(alias), property, project);
                if (column.isPresent() && !isNullChecked(text, path) && reported.add(path)) {
                    violations.add(violation(site.sourceFile(), site.method().getName(),
                            subject(site) + "условие '" + path + " " + operator
                                    + " ...' по колонке, в которой бывает NULL (" + column.get().source() + ")."
                                    + " Сравнение NULL с любым значением дает UNKNOWN, поэтому строки с пустым '"
                                    + property + "' в результат не попадут, хотя заданному значению они не равны;"
                                    + " добавьте OR " + path + " IS NULL либо сравнивайте COALESCE(" + path + ", ...)")
                            .withConfidence(column.get().confidence()));
                }
            }
        }
    }

    // select sum(o.amount) from Order o where ...: по пустой выборке SUM, MAX, MIN и AVG возвращают NULL
    private void checkAggregate(Site site, String query, List<Violation> violations) {
        MethodDeclaration method = site.method();
        String text = SPACES.matcher(query).replaceAll(" ");
        Matcher aggregate = AGGREGATE.matcher(text);
        if (!aggregate.find() || GROUP_BY.matcher(text).find()) {
            return;
        }
        // После агрегата сразу идет FROM: он единственное, что возвращает запрос
        String rest = text.substring(Math.min(text.length(), closingParenthesis(text, aggregate.end() - 1)));
        String result = method.getType().isClassOrInterfaceType()
                ? method.getType().asClassOrInterfaceType().getNameAsString()
                : method.getTypeAsString();
        if (!FROM.matcher(rest).find() || NULL_SAFE_RESULTS.contains(result) || method.getType().isVoidType()) {
            return;
        }
        String function = aggregate.group(1).toUpperCase(Locale.ROOT);
        boolean primitive = method.getType().isPrimitiveType();
        violations.add(violation(site.sourceFile(), method.getName(),
                subject(site) + function + " по пустой выборке возвращает"
                        + " NULL, а не ноль" + (primitive
                        ? ", а метод объявлен с примитивом " + result + " - вернуть null он не может, и вызов"
                        + " закончится исключением AopInvocationException (Null return value ... does not match"
                        + " primitive return type)"
                        : ": метод вернет null, и первое же вычисление с результатом закончится NullPointerException")
                        + "; оберните значение - COALESCE(" + function + "(...), 0) - либо возвращайте Optional")
                .withLevel(primitive ? ErrorLevel.MAJOR : ErrorLevel.MINOR));
    }

    // findByStatusNot(status): Spring Data строит условие status <> ?
    private void checkDerived(Site site, ClassOrInterfaceDeclaration entity, Project project, List<Violation> violations) {
        Matcher derived = DERIVED.matcher(site.name());
        if (!derived.matches()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (String part : AND_OR.split(ORDER_BY.matcher(derived.group(2)).replaceFirst(""))) {
            parts.add(withoutSuffix(part, IGNORE_CASE_SUFFIXES));
        }
        for (String part : parts) {
            String name = withoutSuffix(part, NEGATIVE_SUFFIXES);
            if (name.isEmpty() || name.equals(part) || !withoutSuffix(part, NULL_CHECK_SUFFIXES).equals(part)) {
                continue;
            }
            String suffix = part.substring(name.length());
            String property = Character.toLowerCase(name.charAt(0)) + name.substring(1);
            // findByStatusNotOrStatusIsNull: пустые значения учтены отдельным условием
            Optional<Column> column = isNullHandled(parts, name) ? Optional.empty() : column(entity, property, project);
            if (column.isPresent()) {
                violations.add(violation(site.sourceFile(), site.method().getName(),
                        "Метод '" + site.name() + "': условие " + suffix + " по свойству '"
                                + property + "' Spring Data превращает в <> или NOT IN, а в этой колонке бывает NULL"
                                + " (" + column.get().source() + "). Сравнение NULL с любым значением дает UNKNOWN,"
                                + " поэтому строки с пустым '" + property + "' метод не вернет; добавьте к имени Or"
                                + name + "IsNull либо напишите запрос через @Query с IS NULL или COALESCE")
                        .withConfidence(column.get().confidence()));
            }
        }
    }

    private String subject(Site site) {
        return "Запрос метода '" + site.name() + "': ";
    }

    /**
     * @return текст без первого подходящего окончания либо он же, если ни одно не подошло
     */
    private String withoutSuffix(String text, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (text.endsWith(suffix)) {
                return text.substring(0, text.length() - suffix.length());
            }
        }
        return text;
    }

    // Среди условий метода есть StatusIsNull или StatusNull для того же свойства
    private boolean isNullHandled(List<String> parts, String name) {
        for (String part : parts) {
            if (part.startsWith(name) && NULL_CHECKS.contains(part.substring(name.length()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return текст сравнения с NULL в условии запроса; присваивание в UPDATE ... SET сравнением не является
     */
    private Optional<String> findNullComparison(String query) {
        String plain = MYBATIS_TAG.matcher(query).replaceAll(" ");
        String text = SPACES.matcher(STRING_LITERAL.matcher(plain).replaceAll("''")).replaceAll(" ");
        Matcher comparison = NULL_COMPARISON.matcher(text);
        while (comparison.find()) {
            String clause = "";
            Matcher clauses = CLAUSE.matcher(text.substring(0, comparison.start()));
            while (clauses.find()) {
                clause = clauses.group(1).toLowerCase(Locale.ROOT);
            }
            boolean parameterType = comparison.group(1).toLowerCase(Locale.ROOT).endsWith(JDBC_TYPE);
            if (!clause.isEmpty() && !ASSIGNMENT_CLAUSES.contains(clause) && !parameterType) {
                return Optional.of(comparison.group());
            }
        }
        return Optional.empty();
    }

    // Псевдоним -> класс сущности; псевдонимы, класс которых неизвестен, в карту не попадают
    private Map<String, ClassOrInterfaceDeclaration> aliasesOf(String text, ProjectClasses classes) {
        Map<String, ClassOrInterfaceDeclaration> aliases = new LinkedHashMap<>();
        Matcher root = ROOT.matcher(text);
        while (root.find()) {
            String alias = root.group(2);
            if (!KEYWORDS.contains(alias.toLowerCase(Locale.ROOT))) {
                classes.find(root.group(1)).filter(type -> Annotations.has(type, ENTITY))
                        .ifPresent(type -> aliases.put(alias, type));
            }
        }
        Matcher join = JOIN.matcher(text);
        while (join.find()) {
            ClassOrInterfaceDeclaration owner = aliases.get(join.group(1));
            if (owner != null) {
                JpaProperties.typeOf(owner, Arrays.asList(join.group(2).split("\\.")), classes)
                        .ifPresent(type -> aliases.put(join.group(3), type));
            }
        }
        return aliases;
    }

    /**
     * @param owner сущность, с которой начинается путь; null, если псевдоним не разобран
     * @param path  путь из запроса: status либо customer.email
     * @return колонка, если в ней бывает NULL
     */
    private Optional<Column> column(ClassOrInterfaceDeclaration owner, String path, Project project) {
        if (owner == null) {
            return Optional.empty();
        }
        List<String> segments = Arrays.asList(path.split("\\."));
        Optional<ClassOrInterfaceDeclaration> holder = segments.size() == 1
                ? Optional.of(owner)
                : JpaProperties.typeOf(owner, segments.subList(0, segments.size() - 1), project.classes());
        String property = segments.get(segments.size() - 1);
        return holder.flatMap(type -> declared(type, property, project.classes())
                        .map(found -> nullability(type, found, project)))
                .filter(nullability -> nullability != Nullability.NOT_NULL)
                .map(nullability -> new Column(path, nullability));
    }

    // Поле может быть объявлено в предке (@MappedSuperclass), а колонка все равно лежит в таблице сущности
    private Optional<Declared> declared(ClassOrInterfaceDeclaration entity, String property, ProjectClasses classes) {
        Optional<ClassOrInterfaceDeclaration> owner = Optional.of(entity);
        while (owner.isPresent()) {
            ClassOrInterfaceDeclaration current = owner.get();
            Optional<FieldDeclaration> field = current.getFields().stream()
                    .filter(candidate -> !candidate.isStatic())
                    .filter(candidate -> candidate.getVariables().stream()
                            .anyMatch(variable -> variable.getNameAsString().equals(property)))
                    .findFirst();
            if (field.isPresent()) {
                return Optional.of(new Declared(current, field.get(), property));
            }
            owner = classes.parent(current);
        }
        return Optional.empty();
    }

    private Nullability nullability(ClassOrInterfaceDeclaration entity, Declared declared, Project project) {
        FieldDeclaration field = declared.field();
        boolean declaredNotNull = field.getElementType().isPrimitiveType()
                || Annotations.hasAny(field, NOT_NULL_ANNOTATIONS)
                || Annotations.hasAny(field, COLLECTION_RELATIONS)
                || field.getAnnotations().stream()
                .filter(annotation -> MAPPING_ANNOTATIONS.contains(annotation.getNameAsString()))
                .anyMatch(annotation -> NOT_NULL_MEMBER.matcher(annotation.toString()).find());
        if (declaredNotNull) {
            return Nullability.NOT_NULL;
        }
        Optional<DbSchema.Column> stored = EntityMapping.tableName(entity, project.classes())
                .flatMap(project.schema().visibleFrom(entity)::find)
                .filter(table -> !table.isOpaque())
                .flatMap(table -> EntityMapping.fields(declared.owner(), project.classes()).stream()
                        .filter(mapped -> mapped.variable().getNameAsString().equals(declared.property()))
                        .findFirst()
                        .flatMap(mapped -> table.find(mapped.column())));
        if (stored.isPresent()) {
            return stored.get().notNull() ? Nullability.NOT_NULL : Nullability.BY_SCHEMA;
        }
        return Nullability.BY_MAPPING;
    }

    // o.status IS NULL, o.status IS NOT NULL, COALESCE(o.status, ...): пустые значения в запросе учтены
    private boolean isNullChecked(String text, String path) {
        String quoted = Pattern.quote(path);
        return Pattern.compile(quoted + "\\s+is\\s+(not\\s+)?null\\b|coalesce\\s*\\(\\s*" + quoted + "\\b",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    /**
     * @param from позиция, начиная с которой ищется первая открывающая скобка
     * @return позиция сразу за парной закрывающей скобкой либо конец текста
     */
    private int closingParenthesis(String text, int from) {
        int depth = 0;
        for (int index = text.indexOf('(', from); index >= 0 && index < text.length(); index++) {
            char symbol = text.charAt(index);
            if (symbol == '(') {
                depth++;
            } else if (symbol == ')' && --depth == 0) {
                return index + 1;
            }
        }
        return text.length();
    }
}
