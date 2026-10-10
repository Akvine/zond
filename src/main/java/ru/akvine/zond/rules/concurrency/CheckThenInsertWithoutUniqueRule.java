package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
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
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.NewObjects;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.RepositoryEntities;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "Проверил, что такой записи нет, - вставил": между проверкой и вставкой успевает пройти другой запрос.
 * Спасает только уникальное ограничение в базе, поэтому код сверяется со схемой из миграций.
 */
@Component
public class CheckThenInsertWithoutUniqueRule extends AbstractContextRule {
    private static final Set<String> SAVE_METHODS = Set.of("save", "saveAndFlush", "saveAll", "saveAllAndFlush");
    private static final Set<String> PRESENCE_CHECKS = Set.of("isPresent", "isEmpty", "ifPresent", "ifPresentOrElse");
    private static final Set<String> KEY_ANNOTATIONS = Set.of("Id", "EmbeddedId", "NaturalId");
    private static final String TABLE = "Table";
    private static final String UNIQUE_CONSTRAINT = "UniqueConstraint";
    private static final String UNIQUE = "unique";
    private static final String TRUE = "true";
    private static final String SERIALIZABLE = "SERIALIZABLE";
    private static final Set<String> LOCK_METHODS = Set.of("lock", "tryLock", "lockInterruptibly");
    private static final Pattern DUPLICATE_EXCEPTION = Pattern.compile(
            ".*(DataIntegrityViolation|DuplicateKey|ConstraintViolation).*");

    @Override
    public String code() {
        return RuleCodes.CHECK_THEN_INSERT_WITHOUT_UNIQUE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет код со схемой БД и ищет вставку после проверки существования, не подкрепленную уникальным ограничением";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        DbSchema schema = DbSchema.of(context);
        ProjectClasses classes = ProjectClasses.of(context.sources());
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (method.getBody().isPresent() && !isProtected(method)) {
                    check(sourceFile, method, schema, classes, violations);
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
        return ErrorType.CONCURRENCY;
    }

    private void check(
            SourceFile sourceFile, MethodDeclaration method, DbSchema schema, ProjectClasses classes,
            List<Violation> violations) {
        List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
        for (MethodCallExpr check : calls) {
            Optional<DerivedQueries.Query> query = DerivedQueries.parse(check.getNameAsString());
            if (query.isEmpty() || !Repositories.isRepositoryCall(check) || !isGuard(check, query.get(), method)) {
                continue;
            }
            // Условие сложнее равенства по всем полям - не проверка "есть ли запись с этим значением"
            Optional<List<String>> properties = query.get().equalityProperties();
            boolean existence = query.get().isExistenceCheck();
            Optional<MethodCallExpr> save = calls.stream()
                    .filter(call -> isInsertAfter(call, check))
                    // После existsBy сохраняют заведомо новое; после findBy запись могли найти и обновить -
                    // вставкой считается только сохранение объекта, созданного здесь же
                    .filter(call -> existence || NewObjects.isNew(call.getArgument(0)))
                    .findFirst();
            if (properties.isEmpty() || save.isEmpty()) {
                continue;
            }
            Optional<ClassOrInterfaceDeclaration> entity = RepositoryEntities.entityOf(check.getScope().get(), classes);
            if (entity.isEmpty()) {
                continue;
            }
            describeProblem(entity.get(), properties.get(), schema, classes).ifPresent(problem -> violations.add(
                    violation(sourceFile, check,
                            "Проверка '" + check.getNameAsString() + "' и следом '"
                                    + MethodCalls.receiverName(save.get().getScope().get()) + "."
                                    + save.get().getNameAsString() + "(...)': между ними другой запрос успеет вставить"
                                    + " такую же запись, и обе проверки пройдут. " + problem.text()
                                    + "; добавьте уникальный индекс и обрабатывайте DataIntegrityViolationException")
                            .withConfidence(problem.confidence())));
        }
    }

    /**
     * @param text       что не так с защитой от дубликата
     * @param confidence уверенность: по схеме из миграций отсутствие ограничения видно точно, без нее - нет
     */
    private record Problem(String text, Confidence confidence) {
    }

    private Optional<Problem> describeProblem(
            ClassOrInterfaceDeclaration entity, List<String> properties, DbSchema schema, ProjectClasses classes) {
        List<EntityMapping.MappedField> mapped = EntityMapping.fields(entity, classes);
        List<String> columns = new ArrayList<>();
        for (String property : properties) {
            Optional<EntityMapping.MappedField> field = DerivedQueries.fieldOf(mapped, property);
            // Свойство не нашлось среди полей с колонками (вложенное, унаследованное): судить не о чем
            if (field.isEmpty() || Annotations.hasAny(field.get().field(), KEY_ANNOTATIONS) || isDeclaredUnique(field.get())) {
                return Optional.empty();
            }
            columns.add(field.get().column());
        }
        if (isUniqueByTableAnnotation(entity, columns)) {
            return Optional.empty();
        }

        String where = String.join(", ", columns);
        Optional<DbSchema.Table> table = EntityMapping.tableName(entity, classes).flatMap(schema::find)
                .filter(found -> !found.isOpaque());
        if (table.isEmpty()) {
            return Optional.of(new Problem(
                    "Уникального ограничения на " + where + " у сущности '" + entity.getNameAsString()
                            + "' не объявлено, а миграций для ее таблицы в проекте нет", Confidence.SUSPICION));
        }
        return table.get().isUnique(columns)
                ? Optional.empty()
                : Optional.of(new Problem(
                "Уникального ограничения на " + where + " в таблице '" + table.get().name() + "' нет, поэтому"
                        + " дубликат сохранится", Confidence.PROBABLE));
    }

    private boolean isDeclaredUnique(EntityMapping.MappedField field) {
        return field.mapping()
                .flatMap(mapping -> Queries.member(mapping, UNIQUE))
                .filter(value -> TRUE.equals(value.toString()))
                .isPresent();
    }

    // @Table(uniqueConstraints = @UniqueConstraint(columnNames = {"email", "tenant_id"}))
    private boolean isUniqueByTableAnnotation(ClassOrInterfaceDeclaration entity, List<String> columns) {
        Set<String> wanted = new LinkedHashSet<>();
        columns.forEach(column -> wanted.add(loose(column)));
        Optional<AnnotationExpr> table = Annotations.find(entity, TABLE);
        if (table.isEmpty()) {
            return false;
        }
        return table.get().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> UNIQUE_CONSTRAINT.equals(annotation.getName().getIdentifier()))
                .map(annotation -> annotation.findAll(StringLiteralExpr.class).stream()
                        .map(literal -> loose(literal.getValue()))
                        .toList())
                .anyMatch(key -> !key.isEmpty() && wanted.containsAll(key));
    }

    private String loose(String name) {
        return DbSchema.name(name).replace("_", "");
    }

    // existsBy... и countBy... - сами по себе проверка; findBy... считается проверкой, когда смотрят,
    // нашлось ли что-нибудь, а не работают с найденным
    private boolean isGuard(MethodCallExpr check, DerivedQueries.Query query, MethodDeclaration method) {
        if (query.isExistenceCheck()) {
            return true;
        }
        boolean checkedInline = check.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr outer && PRESENCE_CHECKS.contains(outer.getNameAsString()))
                .isPresent();
        if (checkedInline) {
            return true;
        }
        Optional<String> variable = check.findAncestor(VariableDeclarator.class).map(VariableDeclarator::getNameAsString);
        return variable.isPresent() && method.findAll(IfStmt.class).stream()
                .map(statement -> statement.getCondition().toString())
                .anyMatch(condition -> isPresenceCondition(condition, variable.get()));
    }

    private boolean isPresenceCondition(String condition, String variable) {
        return condition.contains(variable + ".isPresent()") || condition.contains(variable + ".isEmpty()")
                || condition.contains(variable + " == null") || condition.contains(variable + " != null");
    }

    // Сохранение через тот же репозиторий, стоящее после проверки, и не найденной записи, а новой
    private boolean isInsertAfter(MethodCallExpr call, MethodCallExpr check) {
        if (!SAVE_METHODS.contains(call.getNameAsString()) || call.getScope().isEmpty() || call.getArguments().isEmpty()) {
            return false;
        }
        boolean sameRepository = MethodCalls.receiverName(call.getScope().get())
                .equals(MethodCalls.receiverName(check.getScope().get()));
        boolean after = call.getBegin().isPresent() && check.getEnd().isPresent()
                && call.getBegin().get().isAfter(check.getEnd().get());
        Optional<String> found = check.findAncestor(VariableDeclarator.class).map(VariableDeclarator::getNameAsString);
        boolean savesFound = found.isPresent() && call.getArgument(0).findAll(NameExpr.class).stream()
                .anyMatch(name -> name.getNameAsString().equals(found.get()))
                || found.isPresent() && isName(call.getArgument(0), found.get());
        return sameRepository && after && !savesFound;
    }

    private boolean isName(Expression expression, String name) {
        return expression.isNameExpr() && expression.asNameExpr().getNameAsString().equals(name);
    }

    // Гонку уже учли: транзакция упорядочивает запросы, метод работает под блокировкой либо дубликат ловят
    // по ошибке базы
    private boolean isProtected(MethodDeclaration method) {
        boolean serializable = TransactionalAnnotations.findEffective(method)
                .filter(annotation -> annotation.toString().contains(SERIALIZABLE))
                .isPresent();
        boolean locked = method.isSynchronized() || method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> LOCK_METHODS.contains(call.getNameAsString()));
        return serializable || locked || method.findAll(CatchClause.class).stream()
                .anyMatch(clause -> DUPLICATE_EXCEPTION.matcher(clause.getParameter().getType().asString()).matches());
    }
}
